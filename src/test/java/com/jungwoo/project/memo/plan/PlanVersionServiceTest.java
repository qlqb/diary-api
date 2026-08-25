package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * courseId 필터가 SQL이 아니라 서비스에 있으므로(11-period-plan.md §5-3), 그 필터가
 * 정렬을 망가뜨리지 않는지는 여기서 증명해야 한다. 정렬이 곧 "프로젝트 화면의 대표 계획"이다.
 */
@ExtendWith(MockitoExtension.class)
class PlanVersionServiceTest {

    private static final Long USER_ID = 1L;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Mock
    private PlanVersionMapper planVersionMapper;
    @Mock
    private com.jungwoo.project.memo.execution.ExecutionItemMapper executionItemMapper;

    // 코덱은 진짜를 쓴다 — 검증 대상이 "JSON 안의 courseId를 실제로 알아보는가"라서
    // 스텁으로 바꾸면 증명할 것이 남지 않는다.
    private final PlanSnapshotCodec snapshotCodec = new PlanSnapshotCodec();

    private PlanVersionService service() {
        return new PlanVersionService(planVersionMapper, snapshotCodec, executionItemMapper);
    }

    @Test
    void findCoveringDate_noCourseId_returnsMapperOrderUntouched() {
        when(planVersionMapper.findCoveringDate(USER_ID, DATE))
                .thenReturn(List.of(plan("오늘", 6L), plan("이번 주", 7L), plan("8월", 6L)));

        List<PlanVersion> result = service().findCoveringDate(USER_ID, DATE, null);

        assertThat(result).extracting(PlanVersion::getTitle).containsExactly("오늘", "이번 주", "8월");
    }

    @Test
    void findCoveringDate_withCourseId_keepsOnlyPlansHoldingThatCourse() {
        when(planVersionMapper.findCoveringDate(USER_ID, DATE))
                .thenReturn(List.of(plan("영어 오늘", 7L), plan("자료구조 이번 주", 6L)));

        List<PlanVersion> result = service().findCoveringDate(USER_ID, DATE, 6L);

        assertThat(result).extracting(PlanVersion::getTitle).containsExactly("자료구조 이번 주");
    }

    @Test
    void findCoveringDate_withCourseId_preservesRelativeOrderOfSurvivors() {
        // 필터가 짧은 기간 우선 순서를 흔들면 프로젝트 화면의 대표 계획이 바뀐다.
        when(planVersionMapper.findCoveringDate(USER_ID, DATE))
                .thenReturn(List.of(
                        plan("영어 오늘", 7L),
                        plan("자료구조 이번 주", 6L),
                        plan("영어 이번 달", 7L),
                        plan("자료구조 8월", 6L)));

        List<PlanVersion> result = service().findCoveringDate(USER_ID, DATE, 6L);

        assertThat(result).extracting(PlanVersion::getTitle)
                .containsExactly("자료구조 이번 주", "자료구조 8월");
    }

    @Test
    void findCoveringDate_courseHasNoPlan_returnsEmptyNotFallback() {
        // "계획 없음"으로 취급해야 한다. 남의 프로젝트 계획을 대신 보여주면 안 된다.
        when(planVersionMapper.findCoveringDate(USER_ID, DATE))
                .thenReturn(List.of(plan("영어 오늘", 7L)));

        assertThat(service().findCoveringDate(USER_ID, DATE, 6L)).isEmpty();
    }

    // ===== 강도 기본값 승계 (§5-1-2) =====

    @Test
    void resolveIntensity_requestedValueWins_withoutTouchingHistory() {
        assertThat(service().resolveIntensity(USER_ID, PlanIntensity.LIGHT))
                .isEqualTo(PlanIntensity.LIGHT);
        // 요청에 값이 있으면 직전 계획을 읽을 필요조차 없다.
        verifyNoInteractions(planVersionMapper);
    }

    @Test
    void resolveIntensity_noRequest_inheritsFromLatestConfirmedPlan() {
        when(planVersionMapper.findLatestConfirmed(USER_ID))
                .thenReturn(planWithIntensity(PlanIntensity.FOCUSED));

        assertThat(service().resolveIntensity(USER_ID, null)).isEqualTo(PlanIntensity.FOCUSED);
    }

    @Test
    void resolveIntensity_noHistory_fallsBackToNormal() {
        when(planVersionMapper.findLatestConfirmed(USER_ID)).thenReturn(null);

        assertThat(service().resolveIntensity(USER_ID, null)).isEqualTo(PlanIntensity.NORMAL);
    }

    @Test
    void resolveIntensity_latestPlanHasNoIntensity_fallsBackToNormal() {
        // 강도 도입 전에 만든 계획. 더 과거로 거슬러 올라가지 않는다 — "직전 계획을
        // 이어받는다"가 "언젠가 쓴 강도를 되살린다"가 되면 예측할 수 없다.
        when(planVersionMapper.findLatestConfirmed(USER_ID))
                .thenReturn(planWithIntensity(null));

        assertThat(service().resolveIntensity(USER_ID, null)).isEqualTo(PlanIntensity.NORMAL);
    }

    @Test
    void getOwned_missingPlan_throwsNotFound() {
        when(planVersionMapper.findByIdAndUserId(anyLong(), anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service().getOwned(USER_ID, 999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findByPlanKey_unknownKey_throwsNotFound() {
        when(planVersionMapper.findByPlanKeyAndUserId(any(), anyLong())).thenReturn(List.of());

        assertThatThrownBy(() -> service().findByPlanKey(USER_ID, "없는-키"))
                .isInstanceOf(NotFoundException.class);
    }

    private PlanVersion planWithIntensity(PlanIntensity intensity) {
        return PlanVersion.builder()
                .planVersionId(1L).userId(USER_ID).title("직전 계획")
                .intensity(intensity)
                .itemsSnapshot("[]")
                .build();
    }

    private PlanVersion plan(String title, Long courseId) {
        return PlanVersion.builder()
                .planVersionId(1L)
                .userId(USER_ID)
                .title(title)
                .itemsSnapshot("[{\"executionItemId\":1,\"title\":\"항목\",\"courseId\":" + courseId + "}]")
                .build();
    }
}
