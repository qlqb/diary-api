package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.CourseNoteService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.dto.CourseNoteResponse;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 지금 사용자가 보고 있는 화면의 실제 상태를 상담 프롬프트에 싣는다.
 *
 * 이게 없으면 AI는 "오늘 너무 피곤해, 줄여줘" 같은 말에 근거를 가질 수 없다 — 오늘 무엇이
 * 잡혀 있는지 모른 채 새 계획을 지어내게 된다. ContextSnapshotService가 다루는 "대화 기록 +
 * 장기 컨텍스트"와는 성격이 다른, 지금 이 순간의 데이터 스냅샷이다.
 *
 * 대화의 scope와 courseId에 따라 참고 범위가 달라진다:
 * - 프로젝트 대화(courseId 있음): 그 프로젝트의 자료·학습 상태·관련 실행 + 오늘 실행
 * - TODAY: 오늘 실행 + 다음 3일
 * - EXECUTION: 이번 주(7일) 일정
 * - MIXED: 오늘 실행 + 이번 주 일정 + 프로젝트 한 줄 요약
 *
 * 자료 원문은 별도 색인 없이 추출 텍스트 앞부분만 예산 안에서 그대로 싣는다 — "자료를 올리면
 * 곧바로 AI가 그 내용을 쓸 수 있다"는 것이 자료 분석(topic 구조 확정)보다 먼저다.
 *
 * 실행 항목은 항상 #executionItemId를 앞에 붙여 내려준다 — 모델이 기존 항목을 줄이거나
 * 옮기는 조정 후보(adjustments)를 만들 때 그 id를 그대로 참조해야 하기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiWorkspaceContextBuilder {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** 프로젝트 요약/실행 스냅샷 등 "구조화된 상태" 블록 전체의 상한. */
    @Value("${ai.workspace.max-state-chars:3500}")
    private int maxStateChars = 3500;

    /** 자료 추출 원문 발췌 전체의 상한. 위 상한과 별도로 잡는다. */
    @Value("${ai.workspace.max-material-chars:3000}")
    private int maxMaterialChars = 3000;

    private final CourseService courseService;
    private final CourseNoteService courseNoteService;
    private final TopicService topicService;
    private final CourseMaterialMapper courseMaterialMapper;
    private final ExecutionItemMapper executionItemMapper;

    /**
     * @param today 이 턴의 "오늘"(사용자 시간대 기준). 스트리밍 도중 다시 계산하지 않도록
     *              호출부가 이미 확정한 값을 그대로 받는다.
     */
    @Transactional(readOnly = true)
    public String build(AiConversation conversation, Long userId, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        AiProposalTargetScope scope = conversation.getScope() != null
                ? conversation.getScope() : AiProposalTargetScope.TODAY;
        Long courseId = conversation.getCourseId();

        if (courseId != null) {
            appendProjectBlock(sb, userId, courseId, today);
        }

        // 오늘 상태는 프로젝트 대화에서도 함께 싣는다 — 프로젝트 안에서 "오늘 30분 하고 싶어"라고
        // 말했을 때 오늘 남은 시간을 모른 채 제안하면 안 되기 때문이다.
        if (scope != AiProposalTargetScope.EXECUTION) {
            appendTodayBlock(sb, userId, today);
        }
        if (scope == AiProposalTargetScope.EXECUTION || scope == AiProposalTargetScope.MIXED) {
            appendWeekBlock(sb, userId, today);
        }

        String state = truncate(sb.toString(), maxStateChars);

        if (courseId != null) {
            String materials = buildMaterialExcerpt(userId, courseId);
            if (!materials.isEmpty()) {
                state = state + "\n" + materials;
            }
        }
        return state;
    }

    // ===== 프로젝트 =====

    private void appendProjectBlock(StringBuilder sb, Long userId, Long courseId, LocalDate today) {
        Course course;
        try {
            course = courseService.getOwned(userId, courseId);
        } catch (RuntimeException e) {
            // 대화에 연결된 프로젝트가 사라진 경우 — 대화 자체는 계속할 수 있어야 한다.
            log.warn("프로젝트 컨텍스트 생략: userId={}, courseId={}", userId, courseId);
            return;
        }

        sb.append("[프로젝트] ").append(course.getTitle());
        if (course.getGroupLabel() != null) {
            sb.append(" (분류: ").append(course.getGroupLabel()).append(")");
        }
        if (course.getTextbookTitle() != null) {
            sb.append(" · 교재: ").append(course.getTextbookTitle());
        }
        sb.append('\n');

        List<CourseMaterial> materials = courseMaterialMapper.findByCourseIdAndUserId(courseId, userId);
        if (materials.isEmpty()) {
            sb.append("올린 자료: 없음 (자료가 없어도 정상이다. 자료가 없다는 이유로 상담을 미루지 마라)\n");
        } else {
            sb.append("올린 자료 ").append(materials.size()).append("개: ");
            sb.append(materials.stream()
                    .map(m -> m.getOriginalFilename() + "(" + m.getMaterialType() + ")")
                    .collect(Collectors.joining(", ")));
            sb.append('\n');
        }

        List<TopicResponse> tree = topicService.getTopicTree(userId, courseId);
        if (tree.isEmpty()) {
            sb.append("학습 구조: 아직 없음 (자료 구조 분석을 아직 적용하지 않음)\n");
        } else {
            sb.append("학습 구조와 진행 상태 (#뒤는 topicId):\n");
            for (TopicResponse root : tree) {
                appendTopicNode(sb, root, 0);
            }
        }

        List<CourseNoteResponse> notes = courseNoteService.getByCourse(userId, courseId);
        if (!notes.isEmpty()) {
            sb.append("확정된 과목/평가 정보:\n");
            for (CourseNoteResponse note : notes) {
                sb.append("- ").append(note.getLabel()).append(": ").append(note.getDetail()).append('\n');
            }
        }

        List<ExecutionItem> related = executionItemMapper.findByUserIdAndCourseId(
                userId, courseId, today.minusDays(7), today.plusDays(14));
        if (related.isEmpty()) {
            sb.append("이 프로젝트로 잡힌 실행 항목: 없음\n");
        } else {
            sb.append("이 프로젝트로 잡힌 실행 항목:\n");
            for (ExecutionItem item : related) {
                sb.append(renderExecutionLine(item, null));
            }
        }
        sb.append('\n');
    }

    private void appendTopicNode(StringBuilder sb, TopicResponse node, int depth) {
        sb.append("  ".repeat(depth)).append("- #").append(node.getTopicId()).append(' ')
                .append(node.getTitle()).append(" [").append(node.getProgressStatus()).append("]\n");
        if (node.getChildren() != null) {
            for (TopicResponse child : node.getChildren()) {
                appendTopicNode(sb, child, depth + 1);
            }
        }
    }

    // ===== 실행 상태 =====

    private void appendTodayBlock(StringBuilder sb, Long userId, LocalDate today) {
        List<ExecutionItem> items = executionItemMapper.findByUserIdAndDate(userId, today);
        Map<Long, String> projectTitles = projectTitlesOf(userId, items);

        sb.append("[오늘 실행 상태] ").append(today.format(DATE_FMT)).append(' ')
                .append(today.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN)).append('\n');
        if (items.isEmpty()) {
            sb.append("오늘 잡힌 항목이 없다.\n");
        } else {
            for (ExecutionItem item : items) {
                sb.append(renderExecutionLine(item, projectTitles));
            }
        }
        sb.append('\n');
    }

    private void appendWeekBlock(StringBuilder sb, Long userId, LocalDate today) {
        LocalDate monday = today.minusDays((today.getDayOfWeek().getValue() + 6L) % 7);
        LocalDate sunday = monday.plusDays(6);
        List<ExecutionItem> items = executionItemMapper.findByUserIdAndDateRange(userId, monday, sunday);
        Map<Long, String> projectTitles = projectTitlesOf(userId, items);

        sb.append("[이번 주 일정] ").append(monday.format(DATE_FMT)).append(" ~ ").append(sunday.format(DATE_FMT)).append('\n');
        if (items.isEmpty()) {
            sb.append("이번 주에 잡힌 항목이 없다.\n");
        } else {
            for (ExecutionItem item : items) {
                sb.append(renderExecutionLine(item, projectTitles));
            }
        }
        sb.append('\n');
    }

    /**
     * 실행 항목 한 줄. 모델이 조정 후보에서 참조할 수 있도록 항상 #executionItemId로 시작한다.
     * 이미 DONE/CANCELLED인 항목도 그대로 보여준다 — "오늘 뭘 했는지"가 남은 오늘을 조정하는
     * 판단 근거이기 때문이다(대신 상태를 명시한다).
     */
    private String renderExecutionLine(ExecutionItem item, Map<Long, String> projectTitles) {
        StringBuilder line = new StringBuilder();
        line.append("- #").append(item.getExecutionItemId()).append(' ');
        if (item.getScheduledDate() != null) {
            line.append(item.getScheduledDate().format(DATE_FMT)).append(' ');
        }
        if (item.getPlacementType() == PlacementType.TIME_FIXED
                && item.getScheduledStartAt() != null && item.getScheduledEndAt() != null) {
            line.append(item.getScheduledStartAt().format(TIME_FMT)).append('~')
                    .append(item.getScheduledEndAt().format(TIME_FMT)).append(' ');
        } else if (item.getPlacementType() == PlacementType.UNSCHEDULED) {
            line.append("(날짜 미정) ");
        } else {
            line.append("(시각 미정) ");
        }
        line.append(item.getTitle());
        line.append(" [").append(item.getStatus()).append(", ").append(item.getPriority());
        if (item.getExpectedMinutes() != null) {
            line.append(", ").append(item.getExpectedMinutes()).append("분");
        }
        String projectTitle = projectTitles != null && item.getCourseId() != null
                ? projectTitles.get(item.getCourseId()) : null;
        if (projectTitle != null) {
            line.append(", 프로젝트: ").append(projectTitle);
        }
        line.append("]\n");
        return line.toString();
    }

    private Map<Long, String> projectTitlesOf(Long userId, List<ExecutionItem> items) {
        boolean anyCourse = items.stream().anyMatch(i -> i.getCourseId() != null);
        if (!anyCourse) {
            return Map.of();
        }
        return courseService.list(userId).stream()
                .collect(Collectors.toMap(c -> c.getCourseId(), c -> c.getTitle(), (a, b) -> a));
    }

    // ===== 자료 원문 =====

    /**
     * 추출에 성공한 자료의 원문 앞부분을 예산 안에서 붙인다. 자료가 여러 개면 균등하게 나눠
     * 담아 한 파일이 예산을 독점하지 않게 한다. 색인/임베딩은 만들지 않는다 — 지금 필요한 것은
     * "AI가 이 자료를 쓸 수 있다"이지 검색 품질 최적화가 아니다.
     */
    private String buildMaterialExcerpt(Long userId, Long courseId) {
        List<CourseMaterial> materials = courseMaterialMapper.findByCourseIdAndUserId(courseId, userId).stream()
                .filter(m -> m.getExtractionStatus() == ExtractionStatus.SUCCESS)
                .filter(m -> m.getExtractedText() != null && !m.getExtractedText().isBlank())
                .toList();
        if (materials.isEmpty()) {
            return "";
        }

        int perMaterial = Math.max(300, maxMaterialChars / materials.size());
        StringBuilder sb = new StringBuilder("[프로젝트 자료 원문 발췌] (앞부분만 잘라서 싣는다. "
                + "여기 없는 내용을 자료에 있었다고 단정하지 마라)\n");
        for (CourseMaterial material : materials) {
            String text = material.getExtractedText().replaceAll("\\s+", " ").trim();
            sb.append("--- ").append(material.getOriginalFilename()).append(" ---\n");
            sb.append(text, 0, Math.min(perMaterial, text.length()));
            if (text.length() > perMaterial) {
                sb.append(" ...(이하 생략)");
            }
            sb.append('\n');
        }
        return truncate(sb.toString(), maxMaterialChars + 200);
    }

    /** 상한을 넘으면 뒤를 자른다. 잘린 사실을 모델이 알 수 있게 표시를 남긴다. */
    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 20)) + "\n...(이하 생략)...\n";
    }

    /** 이 대화가 어떤 실행 상태를 참고했는지 화면에 표시하기 위한 라벨. */
    public String scopeLabel(AiConversation conversation, String projectTitle) {
        if (conversation.getCourseId() != null) {
            return projectTitle != null ? "프로젝트 · " + projectTitle : "프로젝트";
        }
        return switch (conversation.getScope() == null ? AiProposalTargetScope.TODAY : conversation.getScope()) {
            case EXECUTION -> "이번 주 일정";
            case MIXED -> "전체";
            default -> "오늘";
        };
    }

    /** 완료·취소가 아닌, 지금도 그 자리를 차지하고 있는 항목인지. */
    static boolean isLive(ExecutionItem item) {
        return item.getStatus() != ExecutionStatus.DONE && item.getStatus() != ExecutionStatus.CANCELLED;
    }
}
