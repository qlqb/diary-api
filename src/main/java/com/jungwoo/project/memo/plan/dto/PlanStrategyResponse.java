package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.plan.domain.AdjustedBy;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.Treatment;

import java.util.ArrayList;
import java.util.List;

/**
 * 초안 화면이 "이번 계획은 이렇게 봤어요"를 그리는 데 필요한 만큼의 판단.
 *
 * <p>저장된 {@link PlanStrategy}를 그대로 내보내지 않는다. 근거의 출처 id(evidence.refId),
 * 전략의 출처(strategySource), 참조한 맥락 id는 화면이 쓸 일이 없고, 내보내면 언젠가
 * 화면에 뜬다 — 사용자에게 "context #2"는 아무 뜻도 없는 문자열이다.
 *
 * <p>SKIP 항목도 남긴다. 화면의 "이번에는 제외한 내용" 접힘 영역이 그것을 이유와 함께
 * 보여줘야 하기 때문이다. 조각만 없을 뿐 판단에서 사라진 것이 아니다.
 *
 * <p>(2026-09-17) 목표·도달점·유지한 결정·줄인 범위·가정·질문·읽지 못한 것·달라진 점·기존 항목 결정이 추가됐다.
 * 예전 초안(판단 경로·전략 없음)에서는 비어 있고 화면은 있는 것만 그린다.
 */
public record PlanStrategyResponse(
        String goal,
        String strategySummary,
        List<CourseLine> courses,
        List<TopicLine> topics,
        String reach,
        List<String> keptDecisions,
        List<DeferredLine> deferred,
        List<String> assumptions,
        List<String> openQuestions,
        List<String> unreadNotes,
        List<ChangeLine> changes,
        List<ExistingLine> existingDecisions
) {

    /**
     * @param focus  이 과목에서 무엇에 집중하는가
     * @param reason 왜 이 순서인가
     */
    public record CourseLine(Long courseId, int rank, String focus, String reason) {
    }

    /**
     * @param topicTitle 판단 시점의 항목 이름. 화면이 제외 사유를 보여줄 때 쓴다
     * @param adjustedBy 서버가 되돌렸으면 SERVER. 화면이 "표시에 따라 조정"으로 말한다
     */
    public record TopicLine(
            Long topicId,
            String topicTitle,
            Treatment treatment,
            String reason,
            AdjustedBy adjustedBy
    ) {
    }

    public record DeferredLine(String title, String reason, Long topicId) {
    }

    public record ChangeLine(String what, String why) {
    }

    public record ExistingLine(Long executionItemId, String title, String action, String reason,
                               Integer expectedMinutes, java.time.LocalDate toDate) {
    }

    public static PlanStrategyResponse from(PlanStrategy strategy) {
        if (strategy == null) {
            return null;
        }
        List<CourseLine> courses = new ArrayList<>();
        for (PlanStrategy.CourseStrategy course : strategy.courses() == null ? List.<PlanStrategy.CourseStrategy>of()
                : strategy.courses()) {
            courses.add(new CourseLine(course.courseId(), course.rank(), course.focus(), course.reason()));
        }
        List<TopicLine> topics = new ArrayList<>();
        for (PlanStrategy.TopicTreatment topic : strategy.topics() == null ? List.<PlanStrategy.TopicTreatment>of()
                : strategy.topics()) {
            topics.add(new TopicLine(topic.topicId(), topic.topicTitle(), topic.treatment(),
                    topic.reason(), topic.adjustedBy()));
        }
        List<DeferredLine> deferred = new ArrayList<>();
        for (PlanStrategy.Deferred one : strategy.deferred() == null ? List.<PlanStrategy.Deferred>of() : strategy.deferred()) {
            deferred.add(new DeferredLine(one.title(), one.reason(), one.topicId()));
        }
        List<ChangeLine> changes = new ArrayList<>();
        for (PlanStrategy.Change one : strategy.changes() == null ? List.<PlanStrategy.Change>of() : strategy.changes()) {
            changes.add(new ChangeLine(one.what(), one.why()));
        }
        List<ExistingLine> existing = new ArrayList<>();
        for (PlanStrategy.ExistingDecision one : strategy.existingDecisions() == null
                ? List.<PlanStrategy.ExistingDecision>of() : strategy.existingDecisions()) {
            existing.add(new ExistingLine(one.executionItemId(), one.title(), one.action(), one.reason(),
                    one.expectedMinutes(), one.toDate()));
        }
        return new PlanStrategyResponse(strategy.goal(), strategy.strategySummary(), courses, topics,
                strategy.reach(), orEmpty(strategy.keptDecisions()), deferred, orEmpty(strategy.assumptions()),
                orEmpty(strategy.openQuestions()), orEmpty(strategy.unreadNotes()), changes, existing);
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
