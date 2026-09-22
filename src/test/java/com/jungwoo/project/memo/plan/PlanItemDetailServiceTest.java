package com.jungwoo.project.memo.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiProposalItemMapper;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import com.jungwoo.project.memo.plan.domain.PlanItemDetail;
import com.jungwoo.project.memo.plan.dto.PlanItemDetailResponse;
import com.jungwoo.project.memo.plan.provenance.PlanItemEvidence;
import com.jungwoo.project.memo.plan.provenance.PlanProvenance;
import com.jungwoo.project.memo.plan.provenance.PlanProvenanceCodec;
import com.jungwoo.project.memo.plan.provenance.ProvenanceRepresentation;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.jungwoo.project.memo.plan.provenance.ProvidedSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「자세히」의 갱신 규칙. 근거판(제목·설명·구간 id:해시)이 바뀌면 이전 판은 오래된 것으로 보이고, 저절로 다시 만들지
 * 않으며(GET), 사용자가 요청할 때(POST)만 새 판을 만들되 내 메모는 새 판으로 옮긴다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanItemDetailServiceTest {

    private static final long USER = 7L;
    private static final long ITEM = 11L;

    @Mock
    private AiProposalItemMapper itemMapper;
    @Mock
    private AiProposalMapper proposalMapper;
    @Mock
    private PlanItemDetailMapper detailMapper;
    @Mock
    private MaterialSectionMapper sectionMapper;
    @Mock
    private TopicMaterialLinkMapper topicLinkMapper;
    @Mock
    private AiConsultationClient aiClient;
    @Mock
    private AiUsageLimitService usageLimitService;

    private final PlanProvenanceCodec codec = new PlanProvenanceCodec();
    private PlanItemDetailService service;

    @BeforeEach
    void setUp() {
        service = new PlanItemDetailService(itemMapper, proposalMapper, detailMapper, sectionMapper, topicLinkMapper,
                codec, aiClient, usageLimitService, new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 30);
        ReflectionTestUtils.setField(service, "maxCompletionTokens", 1000);
        ReflectionTestUtils.setField(service, "modelName", "test-model");
        when(aiClient.isConfigured()).thenReturn(true);

        AiProposalItem item = new AiProposalItem();
        item.setProposalItemId(ITEM);
        item.setProposalId(5L);
        item.setUserId(USER);
        item.setOriginalPayload("{\"title\":\"연결 리스트 삭제 구현\",\"description\":\"삭제 함수 작성 · 완료: 테스트 통과\"}");
        item.setEvidenceJson(codec.toJson(PlanItemEvidence.of("gen-1", List.of("s3"), "실습이 있어서", List.of(), List.of(), 0)));
        when(itemMapper.findByIdAndUserId(ITEM, USER)).thenReturn(item);

        AiProposal proposal = new AiProposal();
        proposal.setProposalId(5L);
        proposal.setPlanProvenanceJson(codec.toJson(new PlanProvenance(PlanProvenance.SCHEMA_VERSION, "gen-1",
                LocalDateTime.of(2026, 9, 13, 10, 0), "Asia/Seoul", LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20),
                "AI", "test-model",
                List.of(new ProvidedSource("s3", ProvenanceSourceType.MATERIAL_SECTION, 40L, null, null,
                        ProvenanceRepresentation.EXCERPT, Map.of("title", "실습 3"), "· [문제] 실습 3 (p.36) [s3]")),
                List.of())));
        when(proposalMapper.findByIdAndUserId(5L, USER)).thenReturn(proposal);
        when(sectionMapper.findByIdsAndUserId(any(), eq(USER))).thenReturn(List.of(section("h1")));
        when(detailMapper.insertIgnore(any())).thenReturn(1);
    }

    private static MaterialSection section(String hash) {
        return MaterialSection.builder().sectionId(40L).materialId(9L).userId(USER).fileHash(hash).status("ACTIVE")
                .unitType(TextUnitType.PDF_PAGE).unitStart(36).unitEnd(36).displayTitle("실습 3")
                .excerpt("삭제 함수를 구현하고 결과를 제출하세요").build();
    }

    private static PlanItemDetail stored(long id, String version, String status, String userText) {
        return PlanItemDetail.builder().detailId(id).userId(USER).proposalItemId(ITEM).evidenceVersion(version)
                .stepsJson("[{\"text\":\"옛 단계\",\"refIds\":[],\"sectionIds\":[]}]").status(status).userText(userText)
                .createdAt(LocalDateTime.of(2026, 9, 13, 11, 0)).build();
    }

    private void givenModelSteps() {
        when(aiClient.streamTurn(any(), any(), anyInt())).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                new AssistantMessage("정리했어요.\n" + AiStreamParser.DELIMITER
                        + "\n{\"steps\":[{\"text\":\"새 원문의 실습 3을 푼다\",\"refIds\":[\"s3\"]}]}"))))));
    }

    @Test
    void 원문이_바뀌면_GET은_이전_판을_오래된_것으로_돌려주고_모델을_부르지_않는다() {
        // 저장된 판은 옛 해시(h0) 기준이고, 지금 구간은 h1이다 → 근거판이 다르다.
        when(detailMapper.findByItemAndVersion(eq(ITEM), any(), eq(USER))).thenReturn(null);
        when(detailMapper.findByItem(ITEM, USER)).thenReturn(List.of(stored(3L, "old-version", "CURRENT", "내 메모")));

        PlanItemDetailResponse response = service.forProposalItem(USER, ITEM, false);

        assertThat(response.isAvailable()).isTrue();
        assertThat(response.isStale()).isTrue();
        assertThat(response.getUserText()).isEqualTo("내 메모");
        assertThat(response.getEvidenceVersion()).isNotEqualTo("old-version");
        verify(aiClient, never()).streamTurn(any(), any(), anyInt());
    }

    @Test
    void POST는_새_근거판을_만들고_내_메모를_옮기며_이전_판을_STALE로_내린다() {
        when(detailMapper.findByItemAndVersion(eq(ITEM), any(), eq(USER))).thenReturn(null);
        when(detailMapper.findByItem(ITEM, USER)).thenReturn(List.of(stored(3L, "old-version", "CURRENT", "내 메모")));
        givenModelSteps();

        PlanItemDetailResponse response = service.forProposalItem(USER, ITEM, true);

        ArgumentCaptor<PlanItemDetail> saved = ArgumentCaptor.forClass(PlanItemDetail.class);
        verify(detailMapper).insertIgnore(saved.capture());
        assertThat(saved.getValue().getUserText()).as("메모는 새 판으로 옮긴다").isEqualTo("내 메모");
        assertThat(saved.getValue().getEvidenceVersion()).isNotEqualTo("old-version");
        verify(detailMapper).staleOthers(eq(ITEM), eq(saved.getValue().getEvidenceVersion()), eq(USER));
        assertThat(response.isStale()).isFalse();
        assertThat(response.getSteps()).extracting(PlanItemDetailResponse.Step::text).containsExactly("새 원문의 실습 3을 푼다");
        assertThat(response.getSteps().get(0).sectionIds()).containsExactly(40L);
    }

    @Test
    void T20_자료_선택이_남긴_원문_조회_출처로_새_판을_만들고_메모는_옮기며_계획_항목은_건드리지_않는다() {
        // 새 흐름의 스냅샷: MATERIAL_SECTION 줄에 읽은 범위·조회 방식이 함께 남는다. 상세는 같은 sourceId로 근거를 찾는다.
        AiProposal proposal = new AiProposal();
        proposal.setProposalId(5L);
        proposal.setPlanProvenanceJson(codec.toJson(new PlanProvenance(PlanProvenance.SCHEMA_VERSION, "gen-2",
                LocalDateTime.of(2026, 9, 15, 10, 0), "Asia/Seoul", LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20),
                "AI", "test-model",
                List.of(new ProvidedSource("s3", ProvenanceSourceType.MATERIAL_SECTION, 40L, null, null,
                        ProvenanceRepresentation.EXCERPT,
                        Map.of("title", "실습 3", "retrievedRange", "p.36 원문 전체(820자)", "retrieval", "FULL"),
                        "· [문제] 실습 3 (p.36) · 자료 ds.pdf · 읽은 범위: p.36 원문 전체(820자) [s3]", 7L, null)),
                List.of())));
        when(proposalMapper.findByIdAndUserId(5L, USER)).thenReturn(proposal);
        when(detailMapper.findByItemAndVersion(eq(ITEM), any(), eq(USER))).thenReturn(null);
        when(detailMapper.findByItem(ITEM, USER)).thenReturn(List.of(stored(3L, "old-version", "CURRENT", "내 메모")));
        givenModelSteps();

        PlanItemDetailResponse response = service.forProposalItem(USER, ITEM, true);

        assertThat(response.getSteps().get(0).sectionIds()).containsExactly(40L);
        ArgumentCaptor<PlanItemDetail> saved = ArgumentCaptor.forClass(PlanItemDetail.class);
        verify(detailMapper).insertIgnore(saved.capture());
        assertThat(saved.getValue().getUserText()).isEqualTo("내 메모");
        // 계획 항목(시간·마감·선택·근거)은 읽기만 한다.
        verify(itemMapper).findByIdAndUserId(ITEM, USER);
        org.mockito.Mockito.verifyNoMoreInteractions(itemMapper);
    }

    @Test
    void 같은_근거판이_이미_있으면_POST도_모델을_다시_부르지_않는다() {
        // 첫 호출로 지금 근거판을 알아내고, 그 판이 저장돼 있다고 답하게 한다.
        when(detailMapper.findByItemAndVersion(eq(ITEM), any(), eq(USER))).thenAnswer(inv ->
                stored(3L, inv.getArgument(1), "CURRENT", null));

        PlanItemDetailResponse response = service.forProposalItem(USER, ITEM, true);

        assertThat(response.isStale()).isFalse();
        verify(aiClient, never()).streamTurn(any(), any(), anyInt());
        verify(detailMapper, never()).insertIgnore(any());
    }
}
