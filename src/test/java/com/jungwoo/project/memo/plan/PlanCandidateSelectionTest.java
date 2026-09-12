package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계획 후보 선택(PlanMaterialContextService.select). 표 19·20의 결정적 부분:
 * 학기 중반인데 기록이 없어도 첫 미학습 항목이 후보에 남고, KNOWN/이번만 제외는 각각 범위대로 빠진다.
 */
class PlanCandidateSelectionTest {

    private static PlanMaterialContextService.TopicLine line(long id, Long parent, int depth,
                                                              TopicProgressStatus progress, TopicUserMark mark) {
        return new PlanMaterialContextService.TopicLine(id, parent, "t" + id, id + "주차", null, null, depth,
                progress, mark, null, false);
    }

    private static List<PlanMaterialContextService.TopicLine> semester(int count) {
        List<PlanMaterialContextService.TopicLine> all = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            all.add(line(i, null, 0, TopicProgressStatus.NOT_STARTED, null));
        }
        return all;
    }

    @Test
    void midSemesterWithNoRecords_theFirstUnlearnedTopicSurvivesTheCap() {
        List<PlanMaterialContextService.TopicLine> all = semester(60);
        PlanMaterialContextService.Selection selection =
                PlanMaterialContextService.select(all, Set.of(), Set.of(), 45);

        assertThat(selection.lines()).hasSize(45);
        assertThat(selection.lines().get(0).topicId()).isEqualTo(1L);
        assertThat(selection.lines().get(0).firstUnlearned()).isTrue();
        assertThat(selection.lines().stream().filter(PlanMaterialContextService.TopicLine::firstUnlearned)).hasSize(1);
    }

    @Test
    void inProgressAndAssignmentLinkedTopics_areKeptEvenWhenTheyAreAtTheEnd() {
        List<PlanMaterialContextService.TopicLine> all = semester(60);
        all.set(58, line(59, null, 0, TopicProgressStatus.IN_PROGRESS, null));
        PlanMaterialContextService.Selection selection =
                PlanMaterialContextService.select(all, Set.of(), Set.of(60L), 10);

        List<Long> ids = selection.lines().stream().map(PlanMaterialContextService.TopicLine::topicId).toList();
        assertThat(ids).contains(59L, 60L);
        assertThat(ids).hasSize(10);
        // 원래 순서(트리 순서)는 유지된다 — 우선순위는 무엇을 남길지만 정한다.
        assertThat(ids).isSorted();
    }

    @Test
    void knownAndDeferMarks_areExcludedAndCounted_butNotTheOnesExcludedOnlyThisTime() {
        List<PlanMaterialContextService.TopicLine> all = semester(6);
        all.set(0, line(1, null, 0, TopicProgressStatus.NOT_STARTED, TopicUserMark.KNOWN));
        all.set(1, line(2, null, 0, TopicProgressStatus.NOT_STARTED, TopicUserMark.DEFER));
        PlanMaterialContextService.Selection selection =
                PlanMaterialContextService.select(all, Set.of(3L), Set.of(), 45);

        assertThat(selection.excludedByMark()).isEqualTo(2);
        assertThat(selection.excludedThisTime()).isEqualTo(1);
        List<Long> ids = selection.lines().stream().map(PlanMaterialContextService.TopicLine::topicId).toList();
        assertThat(ids).containsExactly(4L, 5L, 6L);
        // 첫 미학습은 남은 것 중 첫 번째다.
        assertThat(selection.lines().get(0).firstUnlearned()).isTrue();
    }

    @Test
    void ancestorsOfAKeptChild_areKeptToo() {
        List<PlanMaterialContextService.TopicLine> all = new ArrayList<>();
        for (long i = 1; i <= 30; i++) {
            all.add(line(i, null, 0, TopicProgressStatus.LEARNED, null));
        }
        all.add(line(31, null, 0, TopicProgressStatus.LEARNED, null));
        all.add(line(32, 31L, 1, TopicProgressStatus.IN_PROGRESS, null));
        PlanMaterialContextService.Selection selection =
                PlanMaterialContextService.select(all, Set.of(), Set.of(), 5);

        List<Long> ids = selection.lines().stream().map(PlanMaterialContextService.TopicLine::topicId).toList();
        assertThat(ids).contains(31L, 32L);
        assertThat(ids.indexOf(31L)).isLessThan(ids.indexOf(32L));
    }

    @Test
    void whenEverythingFits_nothingIsReordered() {
        List<PlanMaterialContextService.TopicLine> all = semester(10);
        all.set(7, line(8, null, 0, TopicProgressStatus.IN_PROGRESS, null));
        PlanMaterialContextService.Selection selection =
                PlanMaterialContextService.select(all, Set.of(), Set.of(), 45);
        assertThat(selection.lines().stream().map(PlanMaterialContextService.TopicLine::topicId).toList())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }
}
