package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Spec;
import com.jungwoo.project.memo.plan.PlanningContext.CourseContext;
import com.jungwoo.project.memo.plan.PlanningContext.TopicContext;
import com.jungwoo.project.memo.plan.domain.EvidenceType;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.PlanStrategy.CourseStrategy;
import com.jungwoo.project.memo.plan.domain.PlanStrategy.Evidence;
import com.jungwoo.project.memo.plan.domain.PlanStrategy.TopicTreatment;
import com.jungwoo.project.memo.plan.domain.StrategySource;
import com.jungwoo.project.memo.plan.domain.Treatment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 모델 없이 계획 초안을 만드는 결정적 생성기.
 *
 * <p><b>왜 있는가.</b> 이번 변경의 위험은 판단 품질이 아니라 전달 체인이다 — 마감이 제안에서
 * 확정으로, 확정에서 Timefold까지 살아남는가. 그 체인을 모델 호출 뒤에서 검증하면 실패했을 때
 * 원인이 둘(체인이 끊겼는가, 모델이 마감을 안 냈는가)로 갈리고, 재현도 되지 않는다. 그래서
 * 같은 입력에 같은 출력을 내는 생성기를 먼저 두고 체인만 따로 확인한다.
 *
 * <p><b>기준선이기도 하다.</b> 이 생성기는 사전식 규칙만으로도 과목 순서(다음 수업이 빠른 순),
 * 수업 전 마감, 이미 아는 내용 제외를 이미 낸다. 판단층이 <b>추가로</b> 내야 하는 것은
 * 선수지식 선택·압축·행동 다양화 셋이고, 그것이 나오지 않으면 판단층은 값을 하지 못한 것이다.
 *
 * <p><b>하지 않는 것.</b> 자료 분량을 모르므로 블록을 자료 위치대로 쪼개지 않는다. 학습 항목
 * 하나가 블록 하나이고 길이는 설정값 하나다. 분량 정보(페이지 범위·슬라이드 수)는 자료 분석
 * 어디에도 저장되지 않는다 — 지어내는 것보다 "모른다"고 적는 편이 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanBlockGeneratorV0 {

    private static final DateTimeFormatter CLASS_TIME = DateTimeFormatter.ofPattern("M/d HH:mm");

    /** 이 생성기가 만든 판단임을 나중에 알아볼 수 있게 하는 표식. */
    static final String GOAL = "v0 결정적 생성 — 창 안의 학습 항목을 다음 수업 순으로 배치";

    private final PlanningContextBuilder contextBuilder;

    /**
     * 학습 항목 하나에 배정하는 길이(분).
     *
     * <p>자료 분량을 모르니 항목마다 다르게 줄 근거가 없다. 하나의 값을 쓰고 그 사실을
     * reason에 적는다 — 45분은 한 자리에서 집중할 수 있는 길이이면서 배치 격자(15분)에
     * 맞아떨어지는 값이다.
     */
    @Value("${plan.v0.block-minutes:45}")
    private int blockMinutes = 45;

    /**
     * 컨텍스트를 모아 초안을 만든다. 모델을 부르지 않으므로 가용시간이 0이어도 실패하지
     * 않는다 — 다만 그때는 기존 경로와 같이 항목 없이 안내만 돌려준다.
     */
    public Generated generate(Spec spec) {
        PeriodPlanDraftGenerator.validatePeriod(spec.start(), spec.end());
        PlanningContext context = contextBuilder.build(spec);
        return generate(spec, context);
    }

    /** 컨텍스트를 이미 가진 호출부(테스트, 이후의 판단 경로)용. */
    public Generated generate(Spec spec, PlanningContext context) {
        int available = PeriodPlanDraftGenerator.availableMinutes(context.availability().windows());
        int target = spec.intensity().targetMinutesFor(available);
        String confidence = PeriodPlanDraftGenerator.confidenceSummary(context.availability().windows());

        List<ProposalItem> items = new ArrayList<>();
        List<TopicTreatment> treatments = new ArrayList<>();
        List<CourseStrategy> courses = new ArrayList<>();

        int maxItems = spec.maxItems();
        int rank = 0;
        /*
         * 과목 순서는 컨텍스트가 이미 다음 수업이 빠른 순으로 맞춰 두었다. 여기서 다시
         * 정렬하지 않는다 — 같은 규칙을 두 곳에 두면 한쪽만 바뀐다.
         */
        for (CourseContext course : context.courses()) {
            rank++;
            courses.add(new CourseStrategy(course.courseId(), rank,
                    course.nextClassAt() != null
                            ? "다음 수업 " + course.nextClassAt().format(CLASS_TIME) + " 전까지"
                            : "다음 수업이 등록되지 않은 과목",
                    course.nextClassAt() != null
                            ? "다음 수업이 가장 빠른 순서로 놓았어요"
                            : "수업 시각을 몰라 뒤에 두었어요"));

            for (TopicContext topic : inProgressFirst(course.topics())) {
                Treatment treatment = treatmentOf(topic);
                treatments.add(new TopicTreatment(topic.topicId(), treatment, treatments.size() + 1,
                        reasonFor(topic, treatment), evidenceFor(topic, course)));
                if (treatment == Treatment.SKIP) {
                    continue;
                }
                /*
                 * 상한을 넘으면 만들지 않되 취급 판단은 위에서 이미 남겼다 — 잘린 항목이
                 * "판단되지 않은 항목"으로 보이면 안 된다. 정렬이 다음 수업 순이라 잘리는
                 * 것은 항상 덜 급한 쪽이다.
                 */
                if (items.size() >= maxItems) {
                    continue;
                }
                items.add(blockOf(course, topic));
            }
        }

        if (items.isEmpty()) {
            log.info("v0 초안: 만들 블록이 없다. userId={}, {}~{}, 과목={}개",
                    spec.userId(), spec.start(), spec.end(), context.courses().size());
        } else {
            log.info("v0 초안: userId={}, {}~{}, 블록={}개({}분), 가용={}분, 예산={}분, 판단 항목={}개",
                    spec.userId(), spec.start(), spec.end(), items.size(), items.size() * blockMinutes,
                    available, target, treatments.size());
        }

        PlanStrategy strategy = new PlanStrategy(
                GOAL,
                "학습 항목을 하나씩 블록으로 만들고 각 과목의 다음 수업 시작을 마감으로 삼았어요.",
                StrategySource.NEW, null, List.of(), courses, treatments, List.of());

        return new Generated(spec, target, target,
                "v0 생성기는 예산을 조정하지 않아요", false,
                defaultTitle(spec), GOAL, items,
                available, confidence, Math.max(0, available - target), items.isEmpty(),
                false, strategy);
    }

    /**
     * 과목 안에서 <b>이미 시작한 것</b>을 앞에 둔다. 그 뒤는 컨텍스트가 준 순서(주차 순)
     * 그대로다 — List.sort가 안정 정렬이라 같은 그룹 안의 순서는 보존된다.
     *
     * <p>시작해 둔 것을 새로 시작하는 것보다 먼저 잇는 것은 "LEARNED는 다시 시키지 않는다"의
     * 짝이다. 반쯤 열어 둔 것이 여럿 쌓이는 것이 새 항목 하나를 늦게 시작하는 것보다 나쁘다.
     */
    private List<TopicContext> inProgressFirst(List<TopicContext> topics) {
        List<TopicContext> ordered = new ArrayList<>(topics);
        ordered.sort(Comparator.comparingInt(t ->
                t.progressStatus() == TopicProgressStatus.IN_PROGRESS ? 0 : 1));
        return ordered;
    }

    /**
     * 사전식 규칙. 순서가 곧 우선순위다.
     *
     * <p>사용자가 말한 사실(user_mark)이 앱이 관찰한 사실(progress)보다 앞선다 — 앱을 쓰기
     * 전부터 알던 내용은 progress로 표현할 방법이 없기 때문이다.
     *
     * <p>SKIP은 <b>LEARNED</b>와 사용자 표식뿐이다. 시작한 것(IN_PROGRESS)은 빼지 않는다 —
     * 빼면 반쯤 열어 둔 항목이 계획에서 조용히 사라지고, 사용자는 그것을 계획 화면에서
     * 알아차릴 방법이 없다.
     *
     * <p>훑기(SKIM)는 내지 않는다. 그러려면 "얼마나 아는가"를 알아야 하는데 그 판단은
     * 규칙이 아니라 판단층의 몫이다.
     */
    private Treatment treatmentOf(TopicContext topic) {
        if (topic.userMark() == TopicUserMark.KNOWN || topic.userMark() == TopicUserMark.DEFER) {
            return Treatment.SKIP;
        }
        return topic.progressStatus() == TopicProgressStatus.LEARNED ? Treatment.SKIP : Treatment.FULL;
    }

    private String reasonFor(TopicContext topic, Treatment treatment) {
        if (treatment != Treatment.SKIP) {
            return topic.progressStatus() == TopicProgressStatus.IN_PROGRESS
                    ? "이어서 진행"
                    : "아직 시작하지 않은 내용이에요";
        }
        if (topic.userMark() == TopicUserMark.KNOWN) {
            return "이미 알고 있다고 알려주셨어요";
        }
        if (topic.userMark() == TopicUserMark.DEFER) {
            return "이번에는 빼기로 하셨어요";
        }
        return "한 번 마친 내용이에요";
    }

    private List<Evidence> evidenceFor(TopicContext topic, CourseContext course) {
        List<Evidence> evidence = new ArrayList<>();
        if (topic.userMark() != null) {
            evidence.add(new Evidence(EvidenceType.USER_MARK, topic.userMark().name(), topic.topicId()));
        }
        evidence.add(new Evidence(EvidenceType.PROGRESS, topic.progressStatus().name(), topic.topicId()));
        if (course.nextClassAt() != null) {
            evidence.add(new Evidence(EvidenceType.NEXT_CLASS,
                    course.nextClassAt().format(CLASS_TIME) + " " + course.title(), course.nextClassRoutineId()));
        }
        return evidence;
    }

    /**
     * 학습 항목 하나 = 블록 하나.
     *
     * <p>마감은 그 과목의 다음 수업 시작 시각이다. 수업이 없으면 마감도 없다 — 근거 없는
     * 마감을 붙이면 배치가 이유 없이 좁아지고, 왜 좁아졌는지 아무도 설명할 수 없다.
     */
    private ProposalItem blockOf(CourseContext course, TopicContext topic) {
        String where = topic.sourceLocator() != null && !topic.sourceLocator().isBlank()
                ? " (" + topic.sourceLocator() + ")"
                : "";
        String description = "학습 항목 '" + topic.title() + "'" + where + " 학습하기"
                + " · 완료: 내용을 자료 없이 한 문단으로 설명할 수 있음"
                + " · 분량 정보 없음 · 기본 " + blockMinutes + "분";

        return new ProposalItem(
                course.title() + " · " + topic.title(),
                description,
                blockMinutes,
                "SHOULD",
                PlacementType.UNSCHEDULED,
                null, null,
                null, null,
                null, null,
                course.courseId(),
                course.nextClassAt());
    }

    private String defaultTitle(Spec spec) {
        return spec.title() != null && !spec.title().isBlank()
                ? spec.title()
                : spec.start().getMonthValue() + "월 " + spec.start().getDayOfMonth() + "일 ~ "
                        + spec.end().getMonthValue() + "월 " + spec.end().getDayOfMonth() + "일 계획";
    }
}
