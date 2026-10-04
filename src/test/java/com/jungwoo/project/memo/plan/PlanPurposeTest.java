package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.plan.PlanMaterialContextService.ExcludedTopic;
import com.jungwoo.project.memo.plan.selection.PlanCatalogText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계획 목적: 화면 값·상담 합의만으로 정하고, 목적마다 "기록 기준 첫 항목"을 출발점으로 쓸지가 다르다.
 */
class PlanPurposeTest {

    @Test
    void 요청_값과_합의_문장을_목적으로_읽는다() {
        assertThat(PlanPurpose.parse("exam")).isEqualTo(PlanPurpose.EXAM);
        assertThat(PlanPurpose.parse("CATCH_UP")).isEqualTo(PlanPurpose.REVIEW);
        assertThat(PlanPurpose.parse("모름")).isNull();
        assertThat(PlanPurpose.fromBriefText("EXAM: 중간고사 대비")).isEqualTo(PlanPurpose.EXAM);
        assertThat(PlanPurpose.fromBriefText("지난 수업 복습")).isEqualTo(PlanPurpose.REVIEW);
        assertThat(PlanPurpose.fromBriefText("방학 동안 교재 혼자 훑기")).isEqualTo(PlanPurpose.SELF_STUDY);
        assertThat(PlanPurpose.fromBriefText("다음 주 수업 예습")).isEqualTo(PlanPurpose.PREVIEW);
        assertThat(PlanPurpose.fromBriefText("하루 30분")).isNull();
    }

    @Test
    void 복습과_시험은_기록_없는_첫_항목을_출발점으로_쓰지_않는다() {
        assertThat(PlanPurpose.REVIEW.allowsFirstUnlearnedAnchor()).isFalse();
        assertThat(PlanPurpose.EXAM.allowsFirstUnlearnedAnchor()).isFalse();
        assertThat(PlanPurpose.PREVIEW.allowsFirstUnlearnedAnchor()).isTrue();
        assertThat(PlanPurpose.SELF_STUDY.allowsFirstUnlearnedAnchor()).isTrue();
        assertThat(PlanPurpose.EXAM.promptRule()).contains("목차 전체를 시험 범위로").contains("missingInformation");
        assertThat(PlanPurpose.SELF_STUDY.promptRule()).contains("실제 수업 진도를 요구하지 않는다");
    }

    @Test
    void 범위_제외만_있으면_빈_수정_문구를_싣지_않는다() {
        assertThat(PlanCatalogText.excludedText(List.of(new ExcludedTopic(1L, "4장", "SCOPE:중간고사")))).isNull();
        assertThat(PlanCatalogText.excludedText(List.of(new ExcludedTopic(1L, "4장", "SCOPE:중간고사"),
                new ExcludedTopic(2L, "1장", "KNOWN")))).contains("이미 알아요 표시 1개(1장)");
    }
}
