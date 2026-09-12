package com.jungwoo.project.memo.plan.provenance;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.CourseNoteMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import com.jungwoo.project.memo.material.CourseMaterialAnalysisMapper;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Spec;
import com.jungwoo.project.memo.plan.PlanReviewService;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusySource;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 스냅샷이 <b>실제로 보낸 것</b>과 대응하는지 본다.
 *
 * <p>이 스위트가 막으려는 실패는 하나다: 구조는 그럴듯한데 값이 프롬프트와 다른 것. 직렬화만
 * 검사하면 그 실패는 통과한다. 그래서 여기서는 모델에 나간 문자열을 붙잡아 스냅샷의 모든
 * 줄이 그 안에 <b>그대로</b> 있는지, 잘려서 안 나간 줄이 스냅샷에 남지 않았는지 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanProvenanceCaptureTest {

    private static final Long USER_ID = 1L;
    private static final LocalDate START = LocalDate.of(2026, 8, 24);
    private static final LocalDate END = LocalDate.of(2026, 8, 30);

    @Mock
    private AiConsultationClient aiConsultationClient;
    @Mock
    private AiUsageLimitService aiUsageLimitService;
    @Mock
    private PlanReviewService planReviewService;
    @Mock
    private CourseMapper courseMapper;
    @Mock
    private TopicService topicService;
    @Mock
    private CourseNoteMapper courseNoteMapper;
    @Mock
    private CourseMaterialAnalysisMapper analysisMapper;
    @Mock
    private CourseMaterialMapper courseMaterialMapper;
    @Mock
    private ExecutionItemMapper executionItemMapper;
    @Mock
    private AvailabilityEstimateService availabilityEstimateService;

    /*
      후보 선택은 이제 PlanMaterialContextService가 한다. 프롬프트의 학습 항목 줄을 단언하는 테스트가
      topicService mock을 그대로 쓰도록 실제 인스턴스를 만들고, 자료 구간·과제·작업 mapper는 빈 값을 주는
      mock으로 채운다(자료 구간이 없을 때의 줄 모양은 예전과 같다).
    */
    private com.jungwoo.project.memo.plan.PlanMaterialContextService materialContextService;

    @Mock
    private com.jungwoo.project.memo.ai.UserContextMapper userContextMapper;

    private PeriodPlanDraftGenerator generator;

    @BeforeEach
    void setUp() {
        materialContextService = new com.jungwoo.project.memo.plan.PlanMaterialContextService(topicService,
                org.mockito.Mockito.mock(com.jungwoo.project.memo.learning.TopicMaterialLinkMapper.class),
                org.mockito.Mockito.mock(com.jungwoo.project.memo.material.MaterialSectionMapper.class),
                courseMaterialMapper,
                org.mockito.Mockito.mock(com.jungwoo.project.memo.assignment.CourseAssignmentService.class),
                org.mockito.Mockito.mock(com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        generator = new PeriodPlanDraftGenerator(aiConsultationClient, aiUsageLimitService,
                planReviewService, courseMapper, topicService, courseNoteMapper, analysisMapper,
                courseMaterialMapper, executionItemMapper, availabilityEstimateService,
                Clock.fixed(Instant.parse("2026-08-23T09:00:00Z"), ZoneId.of("UTC")),
                materialContextService, userContextMapper);
        ReflectionTestUtils.setField(generator, "maxCompletionTokens", 2000);
        ReflectionTestUtils.setField(generator, "requestTimeoutSeconds", 90);
        ReflectionTestUtils.setField(generator, "modelName", "test-model");
        ReflectionTestUtils.setField(generator, "defaultTimeZoneId", "Asia/Seoul");

        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(courseMapper.findByUserIdAndStatus(anyLong(), any()))
                .thenReturn(List.of(Course.builder().courseId(6L).title("자료구조").build()));
        when(executionItemMapper.findByUserIdAndPlanningRange(anyLong(), any(), any())).thenReturn(List.of());
        when(planReviewService.summarizeLatestForPrompt(anyLong())).thenReturn(null);
        when(analysisMapper.findAppliedByCourseIdAndUserId(anyLong(), anyLong())).thenReturn(List.of());
        when(courseNoteMapper.findByCourseIdAndUserId(anyLong(), anyLong())).thenReturn(List.of());
        when(topicService.getTopicTree(anyLong(), anyLong())).thenReturn(List.of());
        givenAvailability(List.of());
    }

    // ===== 준 것과 남은 것이 대응한다 =====

    @Test
    void everyRecordedSourceLine_appearsVerbatimInThePromptThatWasSent() {
        givenAvailability(List.of(
                new BusyWindow(START.atTime(18, 0), START.atTime(23, 0), "근무",
                        BusySource.COMMITMENT, 17L),
                new BusyWindow(START.plusDays(1).atTime(10, 0), START.plusDays(1).atTime(12, 0),
                        "자료구조 수업", BusySource.ROUTINE_OCCURRENCE, 31L)));
        givenTopics(topic(101L, "재귀", "2주차"), topic(102L, "정렬", "3주차"));
        givenOneItem();

        Generated generated = generator.generate(spec("영어는 빼줘"));

        String prompt = capturedPrompt();
        PlanProvenance provenance = generated.provenance();
        assertThat(provenance).isNotNull();
        assertThat(provenance.schemaVersion()).isEqualTo(PlanProvenance.SCHEMA_VERSION);
        assertThat(provenance.generationId()).startsWith("gen-");
        assertThat(provenance.timezone()).isEqualTo("Asia/Seoul");
        assertThat(provenance.modelName()).isEqualTo("test-model");

        assertThat(provenance.providedSources()).isNotEmpty();
        for (ProvidedSource source : provenance.providedSources()) {
            assertThat(prompt)
                    .as("스냅샷의 줄은 실제 프롬프트에 그대로 있어야 한다: " + source.promptLine())
                    .contains(source.promptLine());
        }

        // 출처 종류가 실제 원본을 가리킨다 — 라벨 문자열이 아니라 행 id다.
        assertThat(provenance.providedSources())
                .filteredOn(s -> s.sourceType() == ProvenanceSourceType.COMMITMENT)
                .extracting(ProvidedSource::sourceId).containsExactly(17L);
        assertThat(provenance.providedSources())
                .filteredOn(s -> s.sourceType() == ProvenanceSourceType.ROUTINE_OCCURRENCE)
                .extracting(ProvidedSource::sourceId).containsExactly(31L);
        assertThat(provenance.providedSources())
                .filteredOn(s -> s.sourceType() == ProvenanceSourceType.TOPIC)
                .extracting(ProvidedSource::sourceId).containsExactly(101L, 102L);

        // 이번 턴에 사용자가 방금 말한 것은 확정된 맥락이 아니라 TURN_INPUT이다.
        assertThat(provenance.providedSources())
                .filteredOn(s -> s.sourceType() == ProvenanceSourceType.TURN_INPUT)
                .extracting(s -> s.providedValue().get("text")).containsExactly("영어는 빼줘");
    }

    /**
     * 프롬프트 상한에 걸려 안 나간 줄은 스냅샷에도 없어야 한다.
     *
     * <p>여기서 반대가 되면 사용자는 "이 정보를 줬다"는 화면을 보면서 실제로는 주지 않은
     * 내용을 근거로 읽게 된다. 그것이 이 기능이 막으려는 바로 그 실패다.
     */
    @Test
    void topicsCutByThePromptLimit_areInNeitherThePromptNorTheSnapshot() {
        List<TopicResponse> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(topic(200L + i, "주제" + i, i + "주차"));
        }
        when(topicService.getTopicTree(anyLong(), anyLong())).thenReturn(many);
        givenOneItem();

        Generated generated = generator.generate(spec(null));

        String prompt = capturedPrompt();
        List<Long> recordedTopicIds = generated.provenance().providedSources().stream()
                .filter(s -> s.sourceType() == ProvenanceSourceType.TOPIC)
                .map(ProvidedSource::sourceId).toList();

        assertThat(recordedTopicIds).as("프롬프트 상한(45줄)까지만 실린다").hasSize(45);
        assertThat(prompt).contains("외 5개");
        assertThat(prompt).as("잘린 주제는 모델에게 가지 않는다").doesNotContain("주제49");
        assertThat(recordedTopicIds).as("잘린 주제는 스냅샷에도 없다").doesNotContain(249L);
    }

    // ===== 당시 구조와 자료 파일은 모델에 준 값과 다른 자리에 남는다 =====

    /**
     * 학습 항목 줄에는 부모 항목과 원본 자료 파일이 함께 기록된다. 둘 다 <b>모델에는 가지
     * 않는다</b> — 프롬프트에는 제목과 위치 문자열만 있고, 파일명·해시는 스냅샷이 원문 탐색을
     * 위해 옆에 붙이는 값이다. 화면이 "AI가 파일을 읽었다"고 말하지 않게 하려면 이 구분이
     * 저장 단계에서부터 지켜져야 한다.
     */
    @Test
    void topicLines_carryParentAndSourceMaterial_withoutSendingThemToTheModel() {
        TopicResponse child = TopicResponse.builder().topicId(102L).parentTopicId(101L).title("소켓의 개념")
                .sourceLocator("2주차").sourceMaterialId(657L).sourceMaterialFilename("네트워크 2주차.pdf").build();
        TopicResponse parent = TopicResponse.builder().topicId(101L).title("네트워크와 소켓 프로그래밍")
                .sourceLocator("2주차").sourceMaterialId(657L).sourceMaterialFilename("네트워크 2주차.pdf")
                .children(List.of(child)).build();
        givenTopics(parent);
        when(courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(any(), anyLong())).thenReturn(List.of(
                com.jungwoo.project.memo.material.domain.CourseMaterial.builder().materialId(657L)
                        .originalFilename("네트워크 2주차.pdf").contentType("application/pdf")
                        .fileHash("abc123").build()));
        givenOneItem();

        Generated generated = generator.generate(spec(null));
        String prompt = capturedPrompt();

        List<ProvidedSource> topics = generated.provenance().providedSources().stream()
                .filter(s -> s.sourceType() == ProvenanceSourceType.TOPIC).toList();
        assertThat(topics).extracting(ProvidedSource::sourceId).containsExactly(101L, 102L);
        assertThat(topics.get(0).parentSourceId()).isNull();
        assertThat(topics.get(1).parentSourceId()).isEqualTo(101L);
        for (ProvidedSource topic : topics) {
            assertThat(topic.material()).isNotNull();
            assertThat(topic.material().materialId()).isEqualTo(657L);
            assertThat(topic.material().filename()).isEqualTo("네트워크 2주차.pdf");
            assertThat(topic.material().fileHash()).isEqualTo("abc123");
            assertThat(topic.material().locator()).isEqualTo("2주차");
            // 모델에 준 값에는 파일 정보가 없다.
            assertThat(topic.providedValue()).doesNotContainKeys("filename", "fileHash", "materialId");
        }
        assertThat(prompt).doesNotContain("네트워크 2주차.pdf").doesNotContain("abc123");
        assertThat(prompt).contains("소켓의 개념 (2주차)");
    }

    /** 자료 행을 못 찾아도 학습 항목이 아는 id와 이름은 남긴다. 자료 연결이 없으면 null이다. */
    @Test
    void topicWithoutAMaterialRow_keepsIdAndName_andTopicWithoutLinkHasNoMaterial() {
        TopicResponse linked = TopicResponse.builder().topicId(101L).title("재귀").sourceLocator("2주차")
                .sourceMaterialId(9L).sourceMaterialFilename("옛 자료.pdf").build();
        TopicResponse unlinked = TopicResponse.builder().topicId(102L).title("정렬").build();
        givenTopics(linked, unlinked);
        when(courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(any(), anyLong())).thenReturn(List.of());
        givenOneItem();

        List<ProvidedSource> topics = generator.generate(spec(null)).provenance().providedSources().stream()
                .filter(s -> s.sourceType() == ProvenanceSourceType.TOPIC).toList();

        assertThat(topics.get(0).material()).isNotNull();
        assertThat(topics.get(0).material().materialId()).isEqualTo(9L);
        assertThat(topics.get(0).material().filename()).isEqualTo("옛 자료.pdf");
        assertThat(topics.get(0).material().fileHash()).isNull();
        assertThat(topics.get(1).material()).isNull();
    }

    // ===== 서버 계산은 출처가 아니다 =====

    @Test
    void availabilityEstimate_isRecordedAsACalculation_notAsAProvidedSource() {
        givenAvailability(List.of(new BusyWindow(START.atTime(18, 0), START.atTime(23, 0), "근무",
                BusySource.COMMITMENT, 17L)));
        givenOneItem();

        Generated generated = generator.generate(spec(null));
        PlanProvenance provenance = generated.provenance();

        assertThat(provenance.serverCalculations())
                .extracting(ServerCalculation::kind)
                .containsExactly(ServerCalculation.ServerCalculationKind.AVAILABILITY_ESTIMATE,
                        ServerCalculation.ServerCalculationKind.STUDY_BUDGET);

        ServerCalculation availability = provenance.serverCalculations().get(0);
        assertThat(availability.providedToModel()).isTrue();
        // 계산에 실제로 들어간 입력을 가리킨다 — 모델에게 연결을 추측시키지 않는다.
        assertThat(availability.inputRefIds()).isNotEmpty();
        assertThat(availability.inputRefIds()).allSatisfy(refId ->
                assertThat(provenance.refIds()).contains(refId));
        // 하루 기본 창과 현재 시각은 가리킬 행이 없다. 전체 재현이 된다고 말하지 않는다.
        assertThat(availability.inputLineage()).isEqualTo(ServerCalculation.InputLineage.PARTIAL);
        assertThat(availability.lineageNote()).isNotBlank();
        assertThat(availability.result()).containsKey("availableMinutes");

        // 추정 결과는 출처 목록에 없다. 등록된 일정과 같은 자리에 두면 추정이 사실로 읽힌다.
        assertThat(provenance.providedSources())
                .extracting(ProvidedSource::sourceType)
                .doesNotContain(ProvenanceSourceType.PLAN_REVIEW)
                .contains(ProvenanceSourceType.COMMITMENT);
    }

    // ===== 없는 인용은 버린다 =====

    @Test
    void refIdsTheModelInvented_areDropped_andCountedWithoutRetrying() {
        givenTopics(topic(101L, "재귀", "2주차"));
        String validRef = "s1";
        givenAiItems("""
                [
                  {"title":"재귀 손으로 풀기","expectedMinutes":40,"priority":"MUST","courseId":6,
                   "scheduledDate":null,"reason":"2주차 진도라서","refIds":["%s","s999","다른회차-3"]}
                ]
                """.formatted(validRef));

        Generated generated = generator.generate(spec(null));

        assertThat(generated.itemEvidence()).hasSize(1);
        PlanItemEvidence evidence = generated.itemEvidence().get(0);
        assertThat(evidence.refIds()).as("이번 회차에 실제로 준 것만 남는다").containsExactly(validRef);
        assertThat(evidence.unknownRefCount()).as("버린 인용은 개수로 남긴다").isEqualTo(2);
        assertThat(evidence.status()).isEqualTo(EvidenceStatus.CURRENT);
        assertThat(evidence.generationId()).isEqualTo(generated.provenance().generationId());

        // ref 검증 실패로 모델을 다시 부르지 않는다 — 되묻기 루프를 만들지 않는다.
        verify(aiConsultationClient, times(1)).streamTurn(any(), any(), anyInt());
    }

    @Test
    void modelEstimates_areKeptApartFromLinkedSources() {
        givenTopics(topic(101L, "재귀", "2주차"));
        givenAiItems("""
                [
                  {"title":"재귀 손으로 풀기","expectedMinutes":40,"priority":"MUST","courseId":6,
                   "scheduledDate":null,"reason":"2주차 진도라서","refIds":[]}
                ]
                """);

        Generated generated = generator.generate(spec(null));
        PlanItemEvidence evidence = generated.itemEvidence().get(0);

        // 근거가 없으면 빈 목록이 정답이다. 있어 보이게 채우지 않는다.
        assertThat(evidence.refIds()).isEmpty();
        assertThat(evidence.serverCalculationIds()).isEmpty();
        assertThat(evidence.aiEstimates()).contains("예상 소요 시간 40분");
        // enum 원문(MUST)이 사용자 화면 문자열로 새지 않는다.
        assertThat(evidence.aiEstimates()).noneSatisfy(e -> assertThat(e).contains("MUST"));
        assertThat(evidence.reason()).isEqualTo("2주차 진도라서");
    }

    @Test
    void itemsAndTheirEvidence_lineUpOneToOne() {
        givenTopics(topic(101L, "재귀", "2주차"));
        givenAiItems("""
                [
                  {"title":"A","expectedMinutes":40,"priority":"MUST","courseId":6,"reason":"가","refIds":[]},
                  {"title":"B","expectedMinutes":30,"priority":"SHOULD","courseId":6,"reason":"나","refIds":[]}
                ]
                """);

        Generated generated = generator.generate(spec(null));

        assertThat(generated.items()).hasSize(2);
        assertThat(generated.itemEvidence()).hasSize(2);
        assertThat(generated.itemEvidence()).extracting(PlanItemEvidence::reason)
                .containsExactly("가", "나");
    }

    // ===== fixture =====

    private Spec spec(String instruction) {
        return new Spec(USER_ID, START, END, PlanIntensity.NORMAL, instruction, null, List.of());
    }

    private void givenAvailability(List<BusyWindow> busy) {
        List<AvailabilityWindow> windows = List.of(new AvailabilityWindow(
                START.atTime(9, 0), START.atTime(9, 0).plusMinutes(925),
                AvailabilitySource.DEFAULT_INFERENCE, AvailabilityConfidence.LOW, "기본 시간대"));
        when(availabilityEstimateService.estimate(anyLong(), any(), any(), any(), any()))
                .thenReturn(new AvailabilityEstimateResult(windows, busy));
    }

    private void givenTopics(TopicResponse... topics) {
        when(topicService.getTopicTree(anyLong(), anyLong())).thenReturn(List.of(topics));
    }

    private TopicResponse topic(Long topicId, String title, String locator) {
        return TopicResponse.builder().topicId(topicId).title(title).sourceLocator(locator).build();
    }

    /** 항목이 하나도 없으면 생성 자체가 실패한다 — 출처만 보려는 테스트의 최소 응답. */
    private void givenOneItem() {
        givenAiItems("""
                [
                  {"title":"한 조각","expectedMinutes":30,"priority":"SHOULD","courseId":6,
                   "scheduledDate":null,"reason":"자리 채우기","refIds":[]}
                ]
                """);
    }

    private void givenAiItems(String itemsJson) {
        String json = "{\"title\":\"이번 주 계획\",\"goalSummary\":null,\"items\":" + itemsJson + "}";
        when(aiConsultationClient.streamTurn(any(), any(), anyInt())).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "초안을 만들었어요\n" + AiStreamParser.DELIMITER + "\n" + json))))));
    }

    /** 모델에 실제로 나간 사용자 프롬프트. 스냅샷을 대조할 유일한 기준이다. */
    private String capturedPrompt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(any(), captor.capture(), anyInt());
        return captor.getValue();
    }
}
