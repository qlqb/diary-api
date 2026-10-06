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

    @Test
    void 메모만_먼저_남기면_단계_없는_행이_생기고_나중에_만든_단계가_같은_행을_채운다() {
        // 메모 저장: 지금 근거판의 행이 없으므로 단계가 빈 행을 만든다.
        when(detailMapper.findByItemAndVersion(eq(ITEM), any(), eq(USER))).thenReturn(null);
        when(detailMapper.findByItem(ITEM, USER)).thenReturn(List.of());

        service.saveMemoForProposalItem(USER, ITEM, "  3번에서 포인터가 헷갈림 ");

        ArgumentCaptor<PlanItemDetail> saved = ArgumentCaptor.forClass(PlanItemDetail.class);
        verify(detailMapper).insertIgnore(saved.capture());
        assertThat(saved.getValue().getStepsJson()).isEqualTo("[]");
        assertThat(saved.getValue().getUserText()).isEqualTo("3번에서 포인터가 헷갈림");
        verify(aiClient, never()).streamTurn(any(), any(), anyInt());

        // 그 행이 있는 상태에서 GET: 안내는 아직 없고(available=false) 만들 수 있으며, 메모는 보인다.
        PlanItemDetail memoOnly = PlanItemDetail.builder().detailId(9L).userId(USER).proposalItemId(ITEM)
                .evidenceVersion(saved.getValue().getEvidenceVersion()).stepsJson("[]").status("CURRENT")
                .userText("3번에서 포인터가 헷갈림").build();
        when(detailMapper.findByItemAndVersion(eq(ITEM), any(), eq(USER))).thenReturn(memoOnly);
        PlanItemDetailResponse before = service.forProposalItem(USER, ITEM, false);
        assertThat(before.isAvailable()).isFalse();
        assertThat(before.isCanGenerate()).isTrue();
        assertThat(before.isStale()).isFalse();
        assertThat(before.getUserText()).isEqualTo("3번에서 포인터가 헷갈림");

        // POST: 새 행을 만들지 않고 같은 행에 단계를 채운다 — 메모와 안내가 한 자리에 남는다.
        givenModelSteps();
        when(detailMapper.findByIdAndUserId(9L, USER)).thenReturn(PlanItemDetail.builder().detailId(9L).userId(USER)
                .proposalItemId(ITEM).evidenceVersion(memoOnly.getEvidenceVersion()).status("CURRENT")
                .stepsJson("[{\"text\":\"새 원문의 실습 3을 푼다\",\"refIds\":[\"s3\"],\"sectionIds\":[40]}]")
                .userText("3번에서 포인터가 헷갈림").build());
        PlanItemDetailResponse after = service.forProposalItem(USER, ITEM, true);

        verify(detailMapper).fillSteps(eq(9L), eq(USER), any(), eq("test-model"));
        verify(detailMapper, org.mockito.Mockito.times(1)).insertIgnore(any());
        assertThat(after.isAvailable()).isTrue();
        assertThat(after.getUserText()).isEqualTo("3번에서 포인터가 헷갈림");
    }

    @Test
    void 부분_수행으로_남은_조각은_원래_조각을_거슬러_같은_제안_항목의_안내를_본다() {
        com.jungwoo.project.memo.execution.ExecutionItemMapper executionItemMapper =
                org.mockito.Mockito.mock(com.jungwoo.project.memo.execution.ExecutionItemMapper.class);
        ReflectionTestUtils.setField(service, "executionItemMapper", executionItemMapper);
        AiProposalItem origin = itemMapper.findByIdAndUserId(ITEM, USER);
        // 원래 조각 100은 제안 항목 ITEM으로 만들어졌다. 남은 조각 200은 제안 원본이 없고 100에서 나왔다.
        when(itemMapper.findByCreatedItemIdAndUserId(200L, USER)).thenReturn(List.of());
        when(itemMapper.findByCreatedItemIdAndUserId(100L, USER)).thenReturn(List.of(origin));
        when(executionItemMapper.findByIdAndUserIdIncludingDeleted(200L, USER)).thenReturn(
                com.jungwoo.project.memo.execution.domain.ExecutionItem.builder().executionItemId(200L)
                        .sourceExecutionItemId(100L).build());

        assertThat(service.originOf(USER, 200L)).isSameAs(origin);
        // 직접 만든 항목(원본도 출처도 없음)은 null — 없는 안내를 지어내지 않는다.
        when(itemMapper.findByCreatedItemIdAndUserId(300L, USER)).thenReturn(List.of());
        when(executionItemMapper.findByIdAndUserIdIncludingDeleted(300L, USER)).thenReturn(
                com.jungwoo.project.memo.execution.domain.ExecutionItem.builder().executionItemId(300L).build());
        assertThat(service.originOf(USER, 300L)).isNull();
    }
}
