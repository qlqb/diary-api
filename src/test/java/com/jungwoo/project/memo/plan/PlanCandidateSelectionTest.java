package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계획 후보 선택(PlanMaterialContextService.select)의 두 단계.
 *
 * <p>증명하려는 것: (1) 진행 중·첫 미학습·과제 연결·사용자 연결은 예산이 얼마든 반드시 들어간다 — 판단 입력에서
 * 빠지면 안 되는 사실이다. (2) 나머지는 첫 미학습 이후부터 예산만큼, 남으면 그 앞. 고정 개수 컷은 없다.
 * (3) 출력은 항상 트리 순서다. (4) KNOWN/DEFER와 이번만 제외는 각각 범위대로 빠진다.
 */
class PlanCandidateSelectionTest {

    private static final int UNIT = 10;

    private static PlanMaterialContextService.TopicLine line(long id, Long parent, int depth,
                                                              TopicProgressStatus progress, TopicUserMark mark) {
        return new PlanMaterialContextService.TopicLine(id, parent, "t" + id, id + "주차", null, null, depth,
                progress, mark, null, false);
    }

    private static PlanMaterialContextService.TopicLine linked(long id, LocalDate due, boolean assignment, boolean user) {
        return new PlanMaterialContextService.TopicLine(id, null, "t" + id, id + "주차", null, null, 0,
                TopicProgressStatus.NOT_STARTED, null, null, false, due, assignment, user);
    }

    private static List<PlanMaterialContextService.TopicLine> semester(int count) {
        List<PlanMaterialContextService.TopicLine> all = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            all.add(line(i, null, 0, TopicProgressStatus.NOT_STARTED, null));
        }
        return all;
    }

    private static PlanMaterialContextService.Selection select(List<PlanMaterialContextService.TopicLine> all,
                                                                Set<Long> exclude, int budgetUnits) {
        return PlanMaterialContextService.select(all, exclude, l -> UNIT, budgetUnits * UNIT);
    }

    private static List<Long> ids(PlanMaterialContextService.Selection selection) {
        return selection.lines().stream().map(PlanMaterialContextService.TopicLine::topicId).toList();
    }

    @Test
    void midSemesterWithNoRecords_theFirstUnlearnedTopicSurvivesTheBudget() {
        List<PlanMaterialContextService.TopicLine> all = semester(60);
        PlanMaterialContextService.Selection selection = select(all, Set.of(), 45);

        assertThat(selection.lines()).hasSize(45);
        assertThat(selection.lines().get(0).topicId()).isEqualTo(1L);
        assertThat(selection.lines().get(0).firstUnlearned()).isTrue();
        assertThat(selection.lines().stream().filter(PlanMaterialContextService.TopicLine::firstUnlearned)).hasSize(1);
        assertThat(selection.budgetHidden()).isEqualTo(15);
    }

    @Test
    void mustIncludeTopics_areKeptEvenWhenTheBudgetIsTiny_andTheyAreAtTheEnd() {
        List<PlanMaterialContextService.TopicLine> all = semester(60);
        all.set(58, line(59, null, 0, TopicProgressStatus.IN_PROGRESS, null));
        all.set(57, linked(58, LocalDate.of(2026, 9, 18), true, false));
        all.set(56, linked(57, null, false, true));
        PlanMaterialContextService.Selection selection = select(all, Set.of(), 2);

        // 예산 2줄인데 반드시 포함이 넷(첫 미학습 1, 사용자 연결 57, 과제 연결 58, 진행 중 59) — 예산을 넘겨서라도 싣는다.
        assertThat(ids(selection)).containsExactly(1L, 57L, 58L, 59L);
        assertThat(selection.mustIncluded()).isEqualTo(4);
        assertThat(selection.overBudget()).isTrue();
    }

    @Test
    void theBudgetFillsForwardFromTheFirstUnlearned_thenBackward() {
        List<PlanMaterialContextService.TopicLine> all = semester(10);
        for (int i = 0; i < 4; i++) {
            all.set(i, line(i + 1, null, 0, TopicProgressStatus.LEARNED, null));
        }
        // 첫 미학습은 5. 예산 8줄 → 5~10(6줄) 뒤에 앞쪽 1, 2(2줄). 출력은 트리 순서.
        PlanMaterialContextService.Selection selection = select(all, Set.of(), 8);

        assertThat(ids(selection)).containsExactly(1L, 2L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(selection.budgetHidden()).isEqualTo(2);
    }

    @Test
    void knownAndDeferMarks_areExcludedAndCounted_butNotTheOnesExcludedOnlyThisTime() {
        List<PlanMaterialContextService.TopicLine> all = semester(6);
        all.set(0, line(1, null, 0, TopicProgressStatus.NOT_STARTED, TopicUserMark.KNOWN));
        all.set(1, line(2, null, 0, TopicProgressStatus.NOT_STARTED, TopicUserMark.DEFER));
        PlanMaterialContextService.Selection selection = select(all, Set.of(3L), 45);

        assertThat(selection.excludedByMark()).isEqualTo(2);
        assertThat(selection.excludedThisTime()).isEqualTo(1);
        assertThat(ids(selection)).containsExactly(4L, 5L, 6L);
        // 첫 미학습은 남은 것 중 첫 번째다.
        assertThat(selection.lines().get(0).firstUnlearned()).isTrue();
    }

    @Test
    void ancestorsOfAKeptChild_areKeptToo_andCountAgainstTheBudget() {
        List<PlanMaterialContextService.TopicLine> all = new ArrayList<>();
        for (long i = 1; i <= 30; i++) {
            all.add(line(i, null, 0, TopicProgressStatus.LEARNED, null));
        }
        all.add(line(31, null, 0, TopicProgressStatus.LEARNED, null));
        all.add(line(32, 31L, 1, TopicProgressStatus.IN_PROGRESS, null));
        PlanMaterialContextService.Selection selection = select(all, Set.of(), 5);

        List<Long> ids = ids(selection);
        assertThat(ids).contains(31L, 32L);
        assertThat(ids.indexOf(31L)).isLessThan(ids.indexOf(32L));
        // 진행 중 32와 조상 31로 2줄, 나머지 3줄은 앞에서(첫 미학습이 없으므로 처음부터).
        assertThat(ids).containsExactly(1L, 2L, 3L, 31L, 32L);
    }

    @Test
    void whenEverythingFits_nothingIsReorderedOrHidden() {
        List<PlanMaterialContextService.TopicLine> all = semester(10);
        all.set(7, line(8, null, 0, TopicProgressStatus.IN_PROGRESS, null));
        PlanMaterialContextService.Selection selection = select(all, Set.of(), 45);
        assertThat(ids(selection)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(selection.budgetHidden()).isZero();
        assertThat(selection.overBudget()).isFalse();
    }

    @Test
    void theRealLargestCourse_fitsInTheDefaultBudget() {
        // 실데이터 최대(웹서버프로그래밍 77개, 제목+위치 2,529자)에 구간 줄을 현실적으로 붙여도 12,000자 안이다.
        List<PlanMaterialContextService.TopicLine> all = new ArrayList<>();
        for (long i = 1; i <= 77; i++) {
            all.add(new PlanMaterialContextService.TopicLine(i, null, "웹서버프로그래밍 " + i + "주차 실습과 과제 안내",
                    i + "주차", null, null, 0, TopicProgressStatus.NOT_STARTED, null, null, false));
        }
        // 실측(2026-09-15 로컬 DB): 항목 줄 평균 33자, 구간 줄 평균 119자, 항목당 최대 2줄. 구간이 붙은 항목은
        // 적용된 변경안이 있는 것뿐이라 소수다 — 여기서는 앞 20개 항목에 2줄씩 붙인 것으로 본다.
        int topicLine = PlanMaterialContextService.lineCost(all.get(0), List.of());
        PlanMaterialContextService.Selection selection = PlanMaterialContextService.select(all, Set.of(),
                l -> topicLine + (l.topicId() <= 20 ? 2 * 119 : 0), 12_000);
        assertThat(selection.budgetHidden()).isZero();
        assertThat(selection.lines()).hasSize(77);
    }
}
