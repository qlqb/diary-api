package com.jungwoo.project.memo.plan.selection;

import com.jungwoo.project.memo.assignment.domain.AssignmentConfirmStatus;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.assignment.domain.DueKind;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.plan.PlanMaterialContextService;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.AssignmentLine;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.ExcludedTopic;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.SectionLine;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.TopicLine;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 카탈로그와 판단 사실을 프롬프트 문장으로 옮긴다. 선택 호출과 계획 호출이 <b>같은 문장</b>을 쓰게 하려고 한 곳에 둔다
 * (선택에서 본 사실과 계획에서 본 사실이 다른 말로 적히면 모델이 둘을 다른 것으로 읽는다).
 *
 * <p>여기서 만드는 구간 줄은 <b>목록 설명</b>이다(제목·역할·위치·짧은 설명). 원문 발췌가 아니다 — 원문은 선택 뒤
 * {@link PlanMaterialRetriever}가 저장된 추출 단위에서 읽는다.
 */
public final class PlanCatalogText {

    /** 사용자 수정 목록을 한 줄에 이름으로 싣는 최대 개수. 넘으면 개수만 더한다(필수 사실도 간결하게). */
    static final int MAX_NAMED_EXCLUDED = 12;
    static final int MAX_TASK_CHARS = 80;

    private PlanCatalogText() {
    }

    public static String topicFlags(TopicLine topic) {
        StringBuilder sb = new StringBuilder();
        if (topic.progress() == TopicProgressStatus.IN_PROGRESS) {
            sb.append(" · 진행 중");
            if (topic.lastStudiedAt() != null) {
                sb.append("(마지막 ").append(topic.lastStudiedAt().toLocalDate()).append(")");
            }
        } else if (topic.progress() == TopicProgressStatus.LEARNED) {
            sb.append(" · 학습 완료");
        }
        if (topic.firstUnlearned()) {
            sb.append(" · ← 첫 미학습");
        }
        if (topic.assignmentLinked()) {
            sb.append(" · 미완료 과제의 항목");
            if (topic.assignmentDue() != null) {
                sb.append("(마감 ").append(topic.assignmentDue()).append(")");
            }
        }
        return sb.toString();
    }

    public static String topicTitle(TopicLine topic) {
        StringBuilder sb = new StringBuilder(topic.title());
        if (topic.locator() != null && !topic.locator().isBlank()) {
            sb.append(" (").append(topic.locator()).append(")");
        }
        return sb.toString();
    }

    /** 후보 구간 한 줄의 본문(핸들·들여쓰기 제외). */
    public static String sectionText(SectionLine line, Map<Long, String> topicHandles, boolean withFilename) {
        MaterialSection s = line.section();
        StringBuilder sb = new StringBuilder("[").append(emptyToOther(line.roleLabels())).append("] ")
                .append(s.getDisplayTitle());
        String locator = s.locator();
        if (!locator.isBlank()) {
            sb.append(" (").append(locator).append(")");
        }
        if (withFilename && line.material() != null) {
            sb.append(" · 자료 ").append(line.material().getOriginalFilename());
        }
        if (s.getTaskText() != null) {
            sb.append(" · 수행: ").append(cut(flat(s.getTaskText()), MAX_TASK_CHARS));
        }
        String excerpt = PlanMaterialContextService.shortExcerpt(s.getExcerpt());
        if (excerpt != null) {
            sb.append(" · 설명: \"").append(excerpt).append("\"");
        }
        if (line.completedAssignment()) {
            sb.append(" · 완료한 과제의 구간");
        } else if (line.openAssignment()) {
            sb.append(" · 미완료 과제의 구간");
        }
        if (line.requested()) {
            sb.append(" · 이번 요청에서 지정한 자료");
        }
        if (topicHandles != null && line.topicIds().size() > 1) {
            sb.append(" · 함께 연결: ").append(line.topicIds().stream().skip(1)
                    .map(topicHandles::get).filter(h -> h != null).collect(Collectors.joining(",")));
        }
        return sb.toString();
    }

    /** 과제 한 줄. 확정/확인 전, 마감의 출처, 완료 여부, 원문 위치는 서버가 붙인 사실이다. */
    public static String assignmentText(AssignmentLine line) {
        CourseAssignment a = line.assignment();
        StringBuilder text = new StringBuilder();
        boolean confirmed = a.getConfirmStatus() == AssignmentConfirmStatus.CONFIRMED;
        if (!confirmed) {
            text.append("(확인 전 — 과제인지 사용자가 아직 답하지 않음) ");
        } else if (a.isCompleted()) {
            text.append("완료한 과제: ");
        } else {
            text.append("과제: ");
        }
        text.append(a.getTitle());
        if (a.hasDue()) {
            text.append(" · 마감 ").append(a.getDueKind() == DueKind.DATETIME
                    ? a.getDueAt().toString().replace('T', ' ') : String.valueOf(a.getDueDate()));
            if (a.getDueSource() != null) {
                text.append(switch (a.getDueSource()) {
                    case SOURCE -> "(원문에 명시)";
                    case USER -> "(사용자 확인)";
                    case ESTIMATED -> "(추정)";
                });
            }
        } else if (a.getDueKind() == DueKind.NONE) {
            text.append(" · 마감 없음");
        } else {
            text.append(" · 마감 미확인");
            if (a.getDueQuote() != null) {
                text.append("(원문: \"").append(cut(flat(a.getDueQuote()), 60)).append("\")");
            }
        }
        if (line.section() != null) {
            String locator = line.section().locator();
            text.append(" · 구간 「").append(line.section().getDisplayTitle()).append("」");
            if (!locator.isBlank()) {
                text.append(" ").append(locator);
            }
        }
        if (line.material() != null) {
            text.append(" · 자료 ").append(line.material().getOriginalFilename());
        }
        if (confirmed && a.isCompleted()) {
            text.append(" — 사용자가 완료함. 과제를 다시 수행하게 하지 않는다");
        }
        return text.toString();
    }

    /** 사용자 수정(표식·이번만 제외)을 한 줄로. 이름은 앞에서 몇 개만. */
    public static String excludedText(List<ExcludedTopic> excluded) {
        if (excluded.isEmpty()) {
            return null;
        }
        List<ExcludedTopic> known = excluded.stream().filter(e -> "KNOWN".equals(e.reason())).toList();
        List<ExcludedTopic> defer = excluded.stream().filter(e -> "DEFER".equals(e.reason())).toList();
        List<ExcludedTopic> thisTime = excluded.stream().filter(e -> "THIS_TIME".equals(e.reason())).toList();
        StringBuilder sb = new StringBuilder("사용자 수정으로 후보에서 뺀 항목 — ");
        boolean any = false;
        if (!known.isEmpty()) {
            sb.append("이미 알아요 표시 ").append(names(known));
            any = true;
        }
        if (!defer.isEmpty()) {
            sb.append(any ? " / " : "").append("나중에 표시 ").append(names(defer));
            any = true;
        }
        if (!thisTime.isEmpty()) {
            sb.append(any ? " / " : "").append("이번 계획에서만 제외 ").append(names(thisTime));
        }
        return sb.toString();
    }

    private static String names(List<ExcludedTopic> list) {
        String shown = list.stream().limit(MAX_NAMED_EXCLUDED).map(ExcludedTopic::title).collect(Collectors.joining(", "));
        return list.size() + "개(" + shown + (list.size() > MAX_NAMED_EXCLUDED ? " 외 " + (list.size() - MAX_NAMED_EXCLUDED) + "개" : "") + ")";
    }

    static String emptyToOther(String roles) {
        return roles == null || roles.isBlank() ? "기타" : roles;
    }

    public static String flat(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").trim();
    }

    public static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
