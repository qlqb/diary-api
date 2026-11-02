package com.jungwoo.project.memo.ai.evidence;

import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 근거 조회가 실패해도 상담은 계속되고, 오류 안내도 근거 블록 상한을 지킨다(2026-10-03 리뷰). */
class ConsultEvidenceFailureTest {

    @Test
    void 조회가_실패하면_상한_안에서만_오류를_알리고_넘치면_아무것도_싣지_않는다() {
        CourseService courses = mock(CourseService.class);
        when(courses.list(any(), eq(CourseStatus.ACTIVE))).thenThrow(new IllegalStateException("db down"));
        ConsultEvidenceService service = new ConsultEvidenceService(courses, null, null, null, null, null, null, null,
                null, null);
        ReflectionTestUtils.setField(service, "passageChars", 6000);

        for (int budget : List.of(0, 50)) {
            ConsultEvidenceService.Gathered g = service.gather(new ConsultEvidenceService.Request(1L, null, "시험 언제야",
                    List.of(), null, false, false, budget));
            assertThat(g.block()).as("budget " + budget).isEmpty();
            assertThat(g.ledger()).isNull();
        }

        ConsultEvidenceService.Gathered roomy = service.gather(new ConsultEvidenceService.Request(1L, null, "시험 언제야",
                List.of(), null, false, false, 5000));
        assertThat(roomy.block()).contains("서버 오류로 이번 턴에는 자료를 확인하지 못했다");
        assertThat(roomy.ledger().toView(java.util.Set.of(), List.of()).gaps())
                .extracting(com.jungwoo.project.memo.ai.consult.ConsultView.Gap::reason).containsExactly("LOOKUP_FAILED");
    }
}
