package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.CourseNoteService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.course.dto.CourseNoteResponse;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import com.jungwoo.project.memo.course.dto.CourseResponse;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.dto.RoutineResponse;
import java.time.DayOfWeek;
import java.util.Set;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
import java.time.Duration;
import java.util.ArrayList;
import com.jungwoo.project.memo.course.domain.CourseNoteCategory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final MaterialLinkMapper materialLinkMapper;
    private final ExecutionItemMapper executionItemMapper;
    private final RoutineService routineService;
    private final RoutineOccurrenceService routineOccurrenceService;
    private final CommitmentService commitmentService;
    private final AvailabilityEstimateService availabilityEstimateService;

    /**
     * @param now 이 턴의 "지금"(사용자 시간대 기준). 스트리밍 도중 다시 계산하지 않도록
     *            호출부가 이미 확정한 값을 그대로 받는다. 날짜뿐 아니라 시각까지 받는 이유는
     *            "예정 시간이 이미 지난 항목"을 컨텍스트에 표시해야 하기 때문이다 — 이게 없으면
     *            AI는 오전 계획이 밀렸다는 사실을 사용자가 말해줘야만 알 수 있다.
     */
    @Transactional(readOnly = true)
    public String build(AiConversation conversation, Long userId, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        StringBuilder sb = new StringBuilder();
        AiProposalTargetScope scope = conversation.getScope() != null
                ? conversation.getScope() : AiProposalTargetScope.TODAY;
        Long courseId = conversation.getCourseId();

        if (courseId != null) {
            // 우선 프로젝트 — 학습 항목까지 자세히.
            appendProjectBlock(sb, userId, courseId, today);
        } else {
            /*
             * 프로젝트가 정해지지 않은 대화에도 활성 프로젝트를 싣는다.
             *
             * 전에는 courseId가 없으면 프로젝트 정보를 통째로 건너뛰었다. 그래서 일정 탭에서
             * "내 프로젝트들 보고 일주일 계획 짜줘"라고 하면 모델이 받는 프로젝트 정보가
             * 0바이트였고, 과목 이름조차 모른 채 "다른 과목/활동 균형 잡기" 같은 일반론을
             * 내놓았다. 계획을 짜라면서 무엇을 계획할지는 안 알려준 셈이다.
             *
             * 여러 프로젝트에 걸친 계획 요청(EXECUTION/MIXED)일 때만 자세히 싣는다. 전부
             * 자세히 넣으면 토큰만 늘고 예산에 걸려 앞쪽만 살아남는다.
             */
            appendProjectsOverviewBlock(sb, userId,
                    scope == AiProposalTargetScope.EXECUTION || scope == AiProposalTargetScope.MIXED,
                    today);
        }

        // 오늘 상태는 프로젝트 대화에서도 함께 싣는다 — 프로젝트 안에서 "오늘 30분 하고 싶어"라고
        // 말했을 때 오늘 남은 시간을 모른 채 제안하면 안 되기 때문이다.
        if (scope != AiProposalTargetScope.EXECUTION) {
            appendTodayBlock(sb, userId, now);
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

    /** "2주차", "7주차 (원문에 '힢' 표기)" 어느 쪽에서도 숫자만 뽑는다. */
    private static final Pattern WEEK_IN_LOCATOR = Pattern.compile("(\\d{1,2})\\s*주차");

    /**
     * 한 프로젝트에 실을 학습 항목 줄 수 상한.
     *
     * <p>계획 경로(PlanDraftService)는 한 과목만 다루므로 30줄까지 쓴다. 여기는 활성
     * 프로젝트 전부가 한 예산(maxStateChars)을 나눠 쓴다 — 7과목이면 30줄씩 210줄이라
     * 예산을 혼자 다 먹고 뒤쪽 프로젝트는 이름조차 못 실린다.
     */
    private static final int MAX_TOPIC_LINES_PER_COURSE = 6;

    /** 평가·시험 줄 수 상한. 담당교수·연구실 같은 것은 계획에 영향을 주지 않아 싣지 않는다. */
    private static final int MAX_ASSESSMENT_LINES_PER_COURSE = 3;

    /**
     * 상세를 붙이다가 이 길이를 넘으면 남은 프로젝트는 이름과 개수만 싣는다.
     *
     * <p>truncate가 뒤를 자르면 뒤쪽 프로젝트는 이름조차 사라진다. 그것보다는 "전부 이름은
     * 있고 앞쪽만 자세한" 편이 낫다 — 모델이 프로젝트가 있다는 사실 자체는 알아야 한다.
     */
    private int detailBudgetChars() {
        return Math.max(0, (int) (maxStateChars * 0.7));
    }

    /**
     * 활성 프로젝트 목록. 보관된 프로젝트는 싣지 않는다 — 계획 대상이 아니다.
     *
     * <p>자료 본문도 싣지 않는다. 그건 프로젝트가 하나로 정해진 대화에서만 의미가 있고,
     * 여기서 전부 넣으면 예산을 혼자 다 쓴다.
     *
     * <p><b>학습 항목을 싣는 이유.</b> 전에는 개수만 실었다("학습 항목 0/16 완료"). 그래서
     * 모델은 과목이 있다는 것만 알고 무엇을 할 차례인지는 몰랐고, "핵심 3개(배열·리스트·
     * 스택)" 같은 일반 자료구조 지식을 지어냈다 — 이 강의계획서의 2주차는 ADT·Big-O였다.
     * 계획 경로(PlanDraftService)는 c1be70c부터 이걸 싣고 있었고 대화 경로만 빠져 있었다.
     */
    private void appendProjectsOverviewBlock(StringBuilder sb, Long userId, boolean detailed,
                                             LocalDate today) {
        List<CourseResponse> courses;
        try {
            courses = courseService.list(userId, CourseStatus.ACTIVE);
        } catch (RuntimeException e) {
            log.warn("프로젝트 목록 컨텍스트 생략: userId={}", userId, e);
            return;
        }
        if (courses.isEmpty()) {
            sb.append("[프로젝트] 활성 프로젝트 없음\n\n");
            return;
        }

        Map<Long, List<RoutineResponse>> routinesByCourse = detailed
                ? classSchedulesByCourse(userId) : Map.of();

        sb.append("[프로젝트] 활성 ").append(courses.size()).append("개 (#뒤는 courseId)\n");
        for (CourseResponse course : courses) {
            sb.append("- #").append(course.getCourseId()).append(' ').append(course.getTitle());
            if (course.getGroupLabel() != null) {
                sb.append(" (").append(course.getGroupLabel()).append(')');
            }
            if (course.getTopicCount() > 0) {
                sb.append(" · 학습 항목 ").append(course.getTopicCount()).append("개");
                if (course.getLearnedTopicCount() > 0) {
                    sb.append("(완료 ").append(course.getLearnedTopicCount()).append(')');
                }
            } else {
                sb.append(" · 학습 구조 아직 없음");
            }
            sb.append('\n');

            if (!detailed) {
                continue;
            }

            List<RoutineResponse> routines = routinesByCourse.getOrDefault(course.getCourseId(), List.of());
            LocalDate semesterStart = semesterStartOf(routines);
            Integer currentWeek = weekNumberOf(semesterStart, today);

            appendCourseHeadLine(sb, course, routines, semesterStart, currentWeek);

            // 예산을 넘겼으면 남은 프로젝트는 이름과 개수까지만. 이름조차 잘리는 것보다 낫다.
            if (sb.length() >= detailBudgetChars()) {
                continue;
            }
            appendCourseTopicLines(sb, userId, course.getCourseId(), currentWeek);
            appendCourseAssessmentLines(sb, userId, course.getCourseId());
        }
        sb.append('\n');
    }

    /** 교재·수업 시간·개강일을 한 줄로. 개강일이 있어야 모델이 지금 몇 주차인지 계산할 수 있다. */
    private void appendCourseHeadLine(StringBuilder sb, CourseResponse course,
                                      List<RoutineResponse> routines, LocalDate semesterStart,
                                      Integer currentWeek) {
        List<String> parts = new ArrayList<>();
        if (course.getTextbookTitle() != null) {
            parts.add("교재 " + course.getTextbookTitle());
        }
        for (RoutineResponse routine : routines) {
            parts.add("수업 " + renderWeekdays(routine.daysOfWeek()) + ' '
                    + routine.startTime().format(TIME_FMT) + '~' + routine.endTime().format(TIME_FMT));
        }
        if (semesterStart != null) {
            String weekPart = currentWeek != null ? " (오늘 " + currentWeek + "주차)" : "";
            parts.add("개강 " + semesterStart.format(DATE_FMT) + weekPart);
        }
        if (!parts.isEmpty()) {
            sb.append("    ").append(String.join(" · ", parts)).append('\n');
        }
    }

    /**
     * 학습 항목. 주차를 알면 이번 주 전후만, 모르면 앞에서부터 자른다.
     *
     * <p>source_locator에 "2주차"처럼 강의 진도 위치가 붙어 있다. 교재 목차가 아니라 강의
     * 진도라서 이 값이 곧 "언제 배우는가"다. 지금 주차 근처만 실으면 모델이 한참 뒤 주차를
     * 당겨오지 않는다 — 계획 경로에서 A/B로 확인된 것과 같은 이유다.
     */
    private void appendCourseTopicLines(StringBuilder sb, Long userId, Long courseId, Integer currentWeek) {
        List<TopicResponse> tree;
        try {
            tree = topicService.getTopicTree(userId, courseId);
        } catch (RuntimeException e) {
            log.warn("학습 항목 컨텍스트 생략: userId={}, courseId={}", userId, courseId, e);
            return;
        }
        if (tree == null || tree.isEmpty()) {
            return;
        }

        List<String> all = new ArrayList<>();
        List<String> nearby = new ArrayList<>();
        collectTopicLines(tree, currentWeek, all, nearby);

        List<String> chosen = !nearby.isEmpty() ? nearby : all;
        int shown = Math.min(chosen.size(), MAX_TOPIC_LINES_PER_COURSE);
        if (shown == 0) {
            return;
        }
        sb.append("    학습 항목")
                .append(!nearby.isEmpty() ? "(이번 주 전후)" : "(앞에서부터)").append(":\n");
        for (int i = 0; i < shown; i++) {
            sb.append("      ").append(chosen.get(i)).append('\n');
        }
        int hidden = all.size() - shown;
        if (hidden > 0) {
            sb.append("      (나머지 ").append(hidden).append("개는 생략)\n");
        }
    }

    /** 트리를 줄 목록으로 펼치면서, 주차를 아는 경우 이번 주 전후만 따로 모은다. */
    private void collectTopicLines(List<TopicResponse> nodes, Integer currentWeek,
                                   List<String> all, List<String> nearby) {
        for (TopicResponse node : nodes) {
            StringBuilder line = new StringBuilder("- ").append(node.getTitle());
            String locator = node.getSourceLocator();
            if (locator != null && !locator.isBlank()) {
                line.append(" (").append(locator).append(')');
            }
            all.add(line.toString());

            Integer week = weekInLocator(locator);
            if (currentWeek != null && week != null
                    && week >= currentWeek - 1 && week <= currentWeek + 2) {
                nearby.add(line.toString());
            }
            if (node.getChildren() != null) {
                collectTopicLines(node.getChildren(), currentWeek, all, nearby);
            }
        }
    }

    /** 평가·시험만. 담당교수·연구실·수업도구는 계획에 영향을 주지 않는다. */
    private void appendCourseAssessmentLines(StringBuilder sb, Long userId, Long courseId) {
        List<CourseNoteResponse> notes;
        try {
            notes = courseNoteService.getByCourse(userId, courseId);
        } catch (RuntimeException e) {
            log.warn("과목 정보 컨텍스트 생략: userId={}, courseId={}", userId, courseId, e);
            return;
        }
        List<String> lines = notes.stream()
                .filter(note -> CourseNoteCategory.ASSESSMENT.name().equals(String.valueOf(note.getCategory())))
                .map(note -> "- " + note.getLabel() + ": " + note.getDetail())
                .distinct()
                .limit(MAX_ASSESSMENT_LINES_PER_COURSE)
                .toList();
        if (lines.isEmpty()) {
            return;
        }
        sb.append("    평가:\n");
        for (String line : lines) {
            sb.append("      ").append(line).append('\n');
        }
    }

    /** 개강일. course_notes에는 없고 그 과목 수업(루틴)의 effective_from이 유일한 근거다. */
    private LocalDate semesterStartOf(List<RoutineResponse> routines) {
        return routines.stream()
                .map(RoutineResponse::effectiveFrom)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
    }

    /** 개강일이 속한 주를 1주차로 센다. */
    private Integer weekNumberOf(LocalDate semesterStart, LocalDate today) {
        if (semesterStart == null || today == null || today.isBefore(semesterStart)) {
            return null;
        }
        LocalDate startMonday = semesterStart.minusDays((semesterStart.getDayOfWeek().getValue() + 6L) % 7);
        return (int) (java.time.temporal.ChronoUnit.WEEKS.between(startMonday, today) + 1);
    }

    private Integer weekInLocator(String locator) {
        if (locator == null) {
            return null;
        }
        Matcher matcher = WEEK_IN_LOCATOR.matcher(locator);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /** 아직 끝나지 않은 반복 일정 중 프로젝트에 묶인 것만. 알바·운동은 프로젝트가 없다. */
    private Map<Long, List<RoutineResponse>> classSchedulesByCourse(Long userId) {
        try {
            return routineService.list(userId).stream()
                    .filter(r -> r.courseId() != null && !r.ended())
                    .collect(Collectors.groupingBy(RoutineResponse::courseId));
        } catch (RuntimeException e) {
            log.warn("수업 일정 컨텍스트 생략: userId={}", userId, e);
            return Map.of();
        }
    }

    private String renderWeekdays(Set<DayOfWeek> days) {
        if (days == null || days.isEmpty()) {
            return "";
        }
        return days.stream().sorted()
                .map(d -> d.getDisplayName(TextStyle.SHORT, Locale.KOREAN))
                .collect(Collectors.joining("·"));
    }

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
            // 자료 성격은 자료가 아니라 이 프로젝트의 링크가 갖는다 — 같은 파일이라도
            // 프로젝트마다 다른 성격일 수 있다.
            Map<Long, MaterialType> typeByMaterialId = materialLinkMapper.findByCourseIdAndUserId(courseId, userId)
                    .stream()
                    .collect(Collectors.toMap(MaterialLink::getMaterialId, MaterialLink::getMaterialType,
                            (a, b) -> a));
            sb.append("올린 자료 ").append(materials.size()).append("개: ");
            sb.append(materials.stream()
                    .map(m -> m.getOriginalFilename() + "(" + typeByMaterialId.get(m.getMaterialId()) + ")")
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

    private void appendTodayBlock(StringBuilder sb, Long userId, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        List<ExecutionItem> items = executionItemMapper.findByUserIdAndDate(userId, today);
        Map<Long, String> projectTitles = projectTitlesOf(userId, items);

        sb.append("[오늘 실행 상태] ").append(today.format(DATE_FMT)).append(' ')
                .append(today.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN))
                .append(" 현재 시각 ").append(now.format(TIME_FMT)).append('\n');
        if (items.isEmpty()) {
            sb.append("오늘 잡힌 항목이 없다.\n");
        } else {
            long overdueCount = items.stream().filter(item -> isOverdue(item, now)).count();
            for (ExecutionItem item : items) {
                sb.append(renderExecutionLine(item, projectTitles, now));
            }
            if (overdueCount > 0) {
                sb.append("예정 시간이 이미 지났는데 아직 결론이 나지 않은 항목이 ").append(overdueCount)
                        .append("개 있다(위 줄의 '예정 시간 지남' 표시). 이건 실패가 아니라 조정할 거리다.\n");
            }
        }
        appendFixedRealityLines(sb, userId, today, today);
        sb.append('\n');
        appendAvailabilityBlock(sb, userId, today, today);
    }

    /**
     * 예정 시간이 이미 지났는데 아직 PLANNED로 남아 있는 항목인지.
     *
     * 시각이 정해진(TIME_FIXED) 항목만 대상이다 — 시각을 정하지 않은 항목은 "오후가 됐다"는
     * 이유만으로 밀린 것이 아니다. DONE/HOLD/CANCELLED/PARTIAL은 이미 사용자가 결론을 낸
     * 상태이므로 대상이 아니다.
     */
    static boolean isOverdue(ExecutionItem item, LocalDateTime now) {
        return item.getStatus() == ExecutionStatus.PLANNED
                && item.getPlacementType() == PlacementType.TIME_FIXED
                && item.getScheduledEndAt() != null
                && !item.getScheduledEndAt().isAfter(now);
    }

    private void appendWeekBlock(StringBuilder sb, Long userId, LocalDate today) {
        LocalDate monday = today.minusDays((today.getDayOfWeek().getValue() + 6L) % 7);
        LocalDate sunday = monday.plusDays(6);
        List<ExecutionItem> items = executionItemMapper.findByUserIdAndDateRange(userId, monday, sunday);
        Map<Long, String> projectTitles = projectTitlesOf(userId, items);

        sb.append("[이번 주 일정] ").append(monday.format(DATE_FMT)).append(" ~ ").append(sunday.format(DATE_FMT)).append('\n');
        if (items.isEmpty()) {
            sb.append("이번 주에 잡힌 실행 항목이 없다.\n");
        } else {
            for (ExecutionItem item : items) {
                sb.append(renderExecutionLine(item, projectTitles));
            }
        }
        appendFixedRealityLines(sb, userId, monday, sunday);
        sb.append('\n');
        appendAvailabilityBlock(sb, userId, today, sunday);
    }

    /**
     * 그 기간에 이미 시간이 차 있는 현실 일정. 수업(반복)과 약속(일회성)을 한 줄씩 낸다.
     *
     * <p>이게 없으면 앱이 아는 것을 모델이 되묻는다 — 실제로 "고정으로 빼야 할 일정(알바·수업
     * ·약속)이 있나요"라고 물었다. 셋 다 앱에 이미 있는 값이고, 물으면 안 되는 것들이다
     * (docs/product-thesis.md 현실층 갱신 원칙 ①).
     *
     * <p><b>#executionItemId를 붙이지 않는다.</b> 실행 조각 줄만 그 접두사를 갖는다 — 모델이
     * 조정 후보(adjustments)에서 참조하는 것이 그 id이고, 수업·약속은 조정 대상이 아니다.
     * 대신 "(수업)" "(약속)"으로 종류를 밝혀 바꿀 수 없는 것임을 알린다.
     */
    private void appendFixedRealityLines(StringBuilder sb, Long userId, LocalDate from, LocalDate to) {
        List<String> lines = new ArrayList<>();

        try {
            for (RoutineOccurrence occurrence : routineOccurrenceService.expand(userId, from, to)) {
                lines.add(String.format("- [이미 등록됨 · 반복 일정] %s %s~%s %s%s%n",
                        occurrence.startAt().toLocalDate().format(DATE_FMT),
                        occurrence.startAt().format(TIME_FMT),
                        occurrence.endAt().format(TIME_FMT),
                        occurrence.title(),
                        occurrence.location() != null ? " @" + occurrence.location() : ""));
            }
        } catch (RuntimeException e) {
            log.warn("수업 일정 컨텍스트 생략: userId={}", userId, e);
        }

        try {
            for (Commitment commitment : commitmentService.findOverlapping(userId, from, to)) {
                lines.add(String.format("- [이미 등록됨 · 약속] %s %s~%s %s%s%n",
                        commitment.getStartAt().toLocalDate().format(DATE_FMT),
                        commitment.getStartAt().format(TIME_FMT),
                        commitment.getEndAt().format(TIME_FMT),
                        commitment.getTitle(),
                        commitment.getLocationText() != null ? " @" + commitment.getLocationText() : ""));
            }
        } catch (RuntimeException e) {
            log.warn("약속 컨텍스트 생략: userId={}", userId, e);
        }

        if (lines.isEmpty()) {
            return;
        }
        /*
         * "이미 등록됨"을 줄마다 붙이고 머리말로도 말한다.
         *
         * 이 줄들을 넣자마자 모델이 그것을 "등록해야 할 일정"으로 읽고 수업·알바를
         * scheduleSuggestions 후보로 다시 냈다(적용하면 routines/one_off_commitments에
         * 중복이 생긴다). 되묻지 말라고만 했지 다시 내지 말라고는 안 한 탓이다.
         *
         * 종류를 "(수업)"이 아니라 "반복 일정"으로 적는 것도 의도다 — 저장소 이름과 맞춰야
         * 모델이 "이건 routines에 이미 있는 것"으로 읽는다.
         */
        sb.append("아래는 이미 앱에 등록된 일정이다(반복 일정=routines, 약속=one_off_commitments). "
                + "새로 만들 대상이 아니며, 후보로 다시 내지 않는다. 계획은 이 시간을 피해서 잡는다.\n");
        lines.stream().sorted().forEach(sb::append);
    }

    /**
     * 남는 시간 추정. 배치가 쓰는 것과 같은 계산을 그대로 읽어 온다.
     *
     * <p>이게 없으면 모델이 "언제 시간 있어요?"를 물을 수밖에 없다. 배치 단계
     * (SchedulePreviewService)는 이미 이 값을 쓰는데, 정작 계획을 만드는 모델은 못 보고
     * 있었다 — 같은 사실을 두 단계가 다르게 알고 있던 셈이다.
     *
     * <p>추정값이라고 명시한다. 기본 추론 창(평일 저녁·주말 낮)에서 수업·약속·이미 잡힌
     * 일정을 뺀 값이고, 사용자가 확정한 사실이 아니다.
     */
    private void appendAvailabilityBlock(StringBuilder sb, Long userId, LocalDate from, LocalDate to) {
        AvailabilityEstimateResult result;
        try {
            result = availabilityEstimateService.estimate(userId, from, to, List.of(), List.of());
        } catch (RuntimeException e) {
            log.warn("가용시간 컨텍스트 생략: userId={}", userId, e);
            return;
        }
        // 컨텍스트 조립이 대화를 깨뜨리지 않는다 — 이 블록이 없어도 상담은 계속돼야 한다.
        if (result == null || result.windows() == null) {
            return;
        }

        sb.append("[남는 시간(추정)] ").append(from.format(DATE_FMT)).append(" ~ ").append(to.format(DATE_FMT))
                .append("\n지난 시간과 위 고정 일정을 뺀 값이다. 사용자가 확정한 값이 아니라 추정이므로 "
                        + "\"확정했어요\"처럼 말하지 않는다.\n");
        if (result.windows().isEmpty()) {
            sb.append("남는 시간이 없다고 추정된다. 이럴 때는 무엇을 줄일지 물어보는 편이 낫다.\n\n");
            return;
        }

        long totalMinutes = 0;
        for (AvailabilityWindow window : result.windows()) {
            long minutes = Duration.between(window.startAt(), window.endAt()).toMinutes();
            totalMinutes += minutes;
            sb.append("- ").append(window.startAt().toLocalDate().format(DATE_FMT)).append(' ')
                    .append(window.startAt().toLocalDate().getDayOfWeek()
                            .getDisplayName(TextStyle.SHORT, Locale.KOREAN)).append(' ')
                    .append(window.startAt().format(TIME_FMT)).append('~')
                    .append(window.endAt().format(TIME_FMT))
                    .append(" (").append(minutes).append("분)\n");
        }
        sb.append("합계 약 ").append(totalMinutes).append("분.\n\n");
    }

    /**
     * 실행 항목 한 줄. 모델이 조정 후보에서 참조할 수 있도록 항상 #executionItemId로 시작한다.
     * 이미 DONE/CANCELLED인 항목도 그대로 보여준다 — "오늘 뭘 했는지"가 남은 오늘을 조정하는
     * 판단 근거이기 때문이다(대신 상태를 명시한다).
     */
    private String renderExecutionLine(ExecutionItem item, Map<Long, String> projectTitles) {
        return renderExecutionLine(item, projectTitles, null);
    }

    private String renderExecutionLine(ExecutionItem item, Map<Long, String> projectTitles, LocalDateTime now) {
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
        if (now != null && isOverdue(item, now)) {
            line.append(", 예정 시간 지남");
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
        return courseService.list(userId, CourseStatus.ACTIVE).stream()
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
