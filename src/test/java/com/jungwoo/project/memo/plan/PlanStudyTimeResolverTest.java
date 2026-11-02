package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.brief.PlanBriefService;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 배치는 생성 때 예산에 쓴 시간 조건(범위 추출이 원문에서 확인한 것)을 먼저 쓴다. */
class PlanStudyTimeResolverTest {

    @Test
    void 요청_맥락에_확인된_시간이_있으면_합의를_읽지_않고_그_시간을_쓴다() {
        AiProposalMapper proposals = mock(AiProposalMapper.class);
        PlanBriefService briefs = mock(PlanBriefService.class);
        AiProposal proposal = new AiProposal();
        proposal.setProposalId(7L);
        proposal.setPlanRequestJson("""
                {"briefId":24,"scopeReport":{"status":"EXTRACTED","scopes":[],
                 "studyTime":{"minutes":null,"perDay":true,"from":"09:00","until":"16:00","quote":"9시부터 4시까지","sentence":"9시부터 4시까지"}}}
                """);
        when(proposals.findByIdAndUserId(7L, 1L)).thenReturn(proposal);
        PlanVersion plan = new PlanVersion();
        plan.setSourceProposalId(7L);

        PlanBriefService.TimeBudget time = new PlanStudyTimeResolver(proposals, briefs).resolve(1L, plan);

        assertThat(time.from()).isEqualTo(LocalTime.of(9, 0));
        assertThat(time.until()).isEqualTo("16:00");
        assertThat(time.minutes()).isNull();
        verify(briefs, never()).loadById(any(), any());
    }

    @Test
    void 확인된_시간이_없는_예전_요청_맥락은_상담_합의로_돌아간다() {
        AiProposalMapper proposals = mock(AiProposalMapper.class);
        PlanBriefService briefs = mock(PlanBriefService.class);
        AiProposal proposal = new AiProposal();
        proposal.setProposalId(7L);
        proposal.setPlanRequestJson("{\"briefId\":24,\"scopeReport\":{\"status\":\"NONE\",\"scopes\":[]}}");
        when(proposals.findByIdAndUserId(7L, 1L)).thenReturn(proposal);
        com.jungwoo.project.memo.ai.brief.PlanBriefItem said = new com.jungwoo.project.memo.ai.brief.PlanBriefItem(1,
                "TIME_BUDGET", "밤 12시까지 공부할게 (쓸 수 있는 시간 하루 · ~24:00)",
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SPEAKER_USER, true, false, false,
                com.jungwoo.project.memo.ai.brief.PlanBriefItem.SCOPE_THIS_DRAFT, 10L, 10L, null, null, null, null, 1,
                java.util.List.of(), java.time.LocalDateTime.of(2026, 10, 19, 9, 0));
        when(briefs.loadById(1L, 24L)).thenReturn(new PlanBriefService.View(24L, 5L, 1, java.util.List.of(said), null));
        PlanVersion plan = new PlanVersion();
        plan.setSourceProposalId(7L);
        plan.setStartDate(java.time.LocalDate.of(2026, 10, 19));
        plan.setEndDate(java.time.LocalDate.of(2026, 10, 19));

        PlanBriefService.TimeBudget time = new PlanStudyTimeResolver(proposals, briefs).resolve(1L, plan);
        assertThat(time.until()).isEqualTo("24:00");
        verify(briefs).loadById(1L, 24L);
    }
}
