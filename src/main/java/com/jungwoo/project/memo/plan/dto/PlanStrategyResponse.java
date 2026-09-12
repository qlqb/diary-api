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
 */
public record PlanStrategyResponse(
        String goal,
        String strategySummary,
        List<CourseLine> courses,
        List<TopicLine> topics
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

    public static PlanStrategyResponse from(PlanStrategy strategy) {
        if (strategy == null) {
            return null;
        }
        List<CourseLine> courses = new ArrayList<>();
        for (PlanStrategy.CourseStrategy course : strategy.courses()) {
            courses.add(new CourseLine(course.courseId(), course.rank(), course.focus(), course.reason()));
        }
        List<TopicLine> topics = new ArrayList<>();
        for (PlanStrategy.TopicTreatment topic : strategy.topics()) {
            topics.add(new TopicLine(topic.topicId(), topic.topicTitle(), topic.treatment(),
                    topic.reason(), topic.adjustedBy()));
        }
        return new PlanStrategyResponse(strategy.goal(), strategy.strategySummary(), courses, topics);
    }
}
