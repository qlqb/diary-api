package com.jungwoo.project.memo.material.linkproposal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.TooManyRequestsException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.domain.ProposalAction;
import com.jungwoo.project.memo.material.dto.LinkProposalResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * status 계약과 실패 계약.
 *
 * 핵심은 "결과가 없음"(NO_CANDIDATES / groups가 LEAVE뿐)과 "만들지 못함"(UNAVAILABLE)을
 * 절대 한 상태로 뭉뚱그리지 않는 것이다 — 프론트가 `다시 시도`를 줄지 판단하는 근거이고,
 * 재시도가 의미 없는 실패(사용량 한도)는 아예 예외로 나가야 한다.
 */
@ExtendWith(MockitoExtension.class)
class MaterialLinkProposalServiceTest {

    private static final Long USER_ID = 1L;

    @Mock private MaterialService materialService;
    @Mock private CourseService courseService;
    @Mock private CourseMaterialMapper courseMaterialMapper;
    @Mock private MaterialLinkMapper materialLinkMapper;
    @Mock private CourseMapper courseMapper;
    @Mock private AiConsultationClient aiConsultationClient;
    @Mock private AiUsageLimitService aiUsageLimitService;

    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Spy private ProposalNormalizer proposalNormalizer = new ProposalNormalizer();

    @InjectMocks
    private MaterialLinkProposalService service;

    /**
     * 로그 한 줄이 이 기능의 관측 수단이라 테스트도 로그를 본다.
     *
     * 30일 자기 검증 동안 "자동 제안이 실제로 카드로 이어졌는지"를 세는 근거가 이 줄뿐이다.
     * 응답에 trigger를 실어 검증하는 방법도 있지만, 그러면 로그 포맷이 조용히 깨져도
     * 테스트가 통과한다.
     */
    private ListAppender<ILoggingEvent> logs;

    /** @InjectMocks는 @Value 필드를 주입하지 않는다 — timeout이 0이면 Flux가 즉시 타임아웃한다. */
    @BeforeEach
    void setUpValueFields() {
        ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 90);
        ReflectionTestUtils.setField(service, "maxCompletionTokens", 4000);
        ReflectionTestUtils.setField(service, "modelName", "test-model");

        logs = new ListAppender<>();
        logs.start();
        serviceLogger().addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        serviceLogger().detachAppender(logs);
    }

    private Logger serviceLogger() {
        return (Logger) LoggerFactory.getLogger(MaterialLinkProposalService.class);
    }

    private String proposalLogLine() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("연결 제안: trigger="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("연결 제안 로그가 남지 않았다"));
    }

    private CourseMaterial material(long materialId, ExtractionStatus status) {
        return CourseMaterial.builder()
                .materialId(materialId).userId(USER_ID)
                .originalFilename("os" + materialId + ".pdf")
                .extractionStatus(status)
                .extractedText("운영체제 강의계획서")
                .createdAt(LocalDateTime.now().minusMinutes(materialId))
                .build();
    }

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private void givenUnlinkedMaterials(CourseMaterial... materials) {
        when(courseMaterialMapper.findAllByUserId(USER_ID)).thenReturn(List.of(materials));
        when(materialLinkMapper.findByUserId(USER_ID)).thenReturn(List.of());
    }

    private void givenModelReplies(String text) {
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(aiConsultationClient.streamTurn(anyString(), anyString(), anyInt()))
                .thenReturn(Flux.just(chatResponse(text)));
        when(courseMapper.findByUserIdAndStatus(eq(USER_ID), anyString())).thenReturn(List.of(
                Course.builder().courseId(10L).title("운영체제").build()));
    }

    private void verifyUsageRecorded(UsageResultStatus status, String errorCode) {
        verify(aiUsageLimitService).record(eq(USER_ID), isNull(), isNull(), anyString(),
                any(), isNull(), any(), eq(status), eq(errorCode), eq("LINK_PROPOSAL"),
                isNull(), anyString(), isNull());
    }

    @Test
    @DisplayName("정리할 미연결 자료가 없으면 모델을 부르지 않고 NO_CANDIDATES다")
    void noCandidates_whenNothingIsUnlinked() {
        when(courseMaterialMapper.findAllByUserId(USER_ID))
                .thenReturn(List.of(material(101L, ExtractionStatus.SUCCESS)));
        when(materialLinkMapper.findByUserId(USER_ID)).thenReturn(List.of(
                MaterialLink.builder().materialId(101L).courseId(10L).userId(USER_ID)
                        .materialType(MaterialType.SYLLABUS).build()));

        LinkProposalResponse response = service.propose(USER_ID, null, null);

        assertThat(response.getStatus()).isEqualTo(ProposalStatus.NO_CANDIDATES);
        assertThat(response.getGroups()).isEmpty();
        assertThat(response.getRemainingMaterialIds()).isEmpty();
        verify(aiConsultationClient, never()).streamTurn(anyString(), anyString(), any());
        // 모델을 부르지 않을 요청으로 한도를 소모시키지 않는다.
        verify(aiUsageLimitService, never()).checkLimit(anyLong());
    }

    @Test
    @DisplayName("추출하지 못한 자료는 후보에서 뺀다 — 파일명만 남은 자료로 이름을 지어내지 않는다")
    void excludesMaterialsWithoutExtractedText() {
        givenUnlinkedMaterials(material(101L, ExtractionStatus.FAILED_NO_TEXT));

        LinkProposalResponse response = service.propose(USER_ID, null, null);

        assertThat(response.getStatus()).isEqualTo(ProposalStatus.NO_CANDIDATES);
        verify(aiConsultationClient, never()).streamTurn(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("AI가 설정되어 있지 않으면 200 + UNAVAILABLE이다 — 예외를 밖으로 던지지 않는다")
    void unavailable_whenAiIsNotConfigured() {
        givenUnlinkedMaterials(material(101L, ExtractionStatus.SUCCESS));
        when(aiConsultationClient.isConfigured()).thenReturn(false);
        when(courseMapper.findByUserIdAndStatus(eq(USER_ID), anyString())).thenReturn(List.of());

        LinkProposalResponse response = service.propose(USER_ID, null, null);

        assertThat(response.getStatus()).isEqualTo(ProposalStatus.UNAVAILABLE);
        verify(aiConsultationClient, never()).streamTurn(anyString(), anyString(), any());
        verify(aiUsageLimitService, never()).record(anyLong(), any(), any(), anyString(),
                any(), any(), any(), any(), any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("모델 호출이 예외면 UNAVAILABLE이고, errorCode 자리에는 예외 메시지가 아니라 코드값이 남는다")
    void unavailable_whenModelCallThrows() {
        givenUnlinkedMaterials(material(101L, ExtractionStatus.SUCCESS));
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(courseMapper.findByUserIdAndStatus(eq(USER_ID), anyString())).thenReturn(List.of());
        when(aiConsultationClient.streamTurn(anyString(), anyString(), anyInt()))
                .thenReturn(Flux.error(new IllegalStateException("upstream is on fire")));

        LinkProposalResponse response = service.propose(USER_ID, null, null);

        assertThat(response.getStatus()).isEqualTo(ProposalStatus.UNAVAILABLE);
        verifyUsageRecorded(UsageResultStatus.FAILED, ErrorCode.AI_GENERATION_FAILED.getCode());
    }

    @Test
    @DisplayName("구분자가 없는 응답은 UNAVAILABLE이다")
    void unavailable_whenDelimiterIsMissing() {
        givenUnlinkedMaterials(material(101L, ExtractionStatus.SUCCESS));
        givenModelReplies("네, 정리해드릴게요. 운영체제로 묶으면 좋겠습니다.");

        LinkProposalResponse response = service.propose(USER_ID, null, null);

        assertThat(response.getStatus()).isEqualTo(ProposalStatus.UNAVAILABLE);
        verifyUsageRecorded(UsageResultStatus.FAILED, ErrorCode.AI_GENERATION_FAILED.getCode());
    }

    @Test
    @DisplayName("구분자 뒤가 JSON이 아니면 UNAVAILABLE이다")
    void unavailable_whenJsonIsBroken() {
        givenUnlinkedMaterials(material(101L, ExtractionStatus.SUCCESS));
        givenModelReplies("<<<AI_STRUCTURED>>>\n{ \"groups\": [ ");

        LinkProposalResponse response = service.propose(USER_ID, null, null);

        assertThat(response.getStatus()).isEqualTo(ProposalStatus.UNAVAILABLE);
        verifyUsageRecorded(UsageResultStatus.FAILED, ErrorCode.AI_GENERATION_FAILED.getCode());
    }

    /**
     * 모델이 아무것도 묶지 못해도 UNAVAILABLE이 아니다.
     *
     * 보정이 입력 자료를 반드시 LEAVE로 되살리므로 "정상 응답인데 groups가 빈 배열"이라는
     * 상태는 실제로 만들어지지 않는다 — 판단하지 못한 자료를 숨기지 않기 때문이다. 그래서
     * 여기서 지키는 계약은 "묶을 게 없었다"와 "만들지 못했다"가 갈린다는 것 자체다.
     */
    @Test
    @DisplayName("모델이 빈 groups를 줘도 GENERATED다 — 자료는 LEAVE로 되살아난다")
    void generated_whenModelGroupsNothing() {
        givenUnlinkedMaterials(material(101L, ExtractionStatus.SUCCESS));
        givenModelReplies("<<<AI_STRUCTURED>>>\n{\"groups\":[]}");

        LinkProposalResponse response = service.propose(USER_ID, null, null);

        assertThat(response.getStatus()).isEqualTo(ProposalStatus.GENERATED);
        assertThat(response.getGroups()).singleElement()
                .satisfies(group -> assertThat(group.getAction()).isEqualTo(ProposalAction.LEAVE));
        verifyUsageRecorded(UsageResultStatus.SUCCESS, null);
    }

    @Test
    @DisplayName("사용량 한도 초과는 UNAVAILABLE로 감싸지 않고 그대로 전파한다 — 다시 눌러도 같은 결과다")
    void propagatesUsageLimitException() {
        givenUnlinkedMaterials(material(101L, ExtractionStatus.SUCCESS));
        when(courseMapper.findByUserIdAndStatus(eq(USER_ID), anyString())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new TooManyRequestsException(ErrorCode.AI_USAGE_LIMIT_EXCEEDED))
                .when(aiUsageLimitService).checkLimit(USER_ID);

        assertThatThrownBy(() -> service.propose(USER_ID, null, null))
                .isInstanceOfSatisfying(TooManyRequestsException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_USAGE_LIMIT_EXCEEDED));

        verify(aiConsultationClient, never()).streamTurn(anyString(), anyString(), any());
        verify(aiUsageLimitService, never()).record(anyLong(), any(), any(), anyString(),
                any(), any(), any(), any(), any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("후보가 상한을 넘으면 최신순으로 자르고 나머지 id를 커서로 돌려준다")
    void capsCandidatesAndReturnsRemainingIds() {
        CourseMaterial[] materials = IntStream.rangeClosed(1, 15)
                .mapToObj(i -> material(100L + i, ExtractionStatus.SUCCESS))
                .toArray(CourseMaterial[]::new);
        givenUnlinkedMaterials(materials);
        givenModelReplies("<<<AI_STRUCTURED>>>\n{\"groups\":[]}");

        LinkProposalResponse response = service.propose(USER_ID, null, null);

        assertThat(response.getStatus()).isEqualTo(ProposalStatus.GENERATED);
        assertThat(response.getRemainingMaterialIds()).containsExactly(113L, 114L, 115L);
        assertThat(response.getGroups()).singleElement()
                .satisfies(group -> assertThat(group.getMembers()).hasSize(12));
    }

    // ===== 호출 경로 로그 =====

    @Test
    @DisplayName("trigger 없이 보낸 요청은 MANUAL로 남는다 — 기존 호출이 깨지지 않는다")
    void treatsMissingTriggerAsManual() {
        givenUnlinkedMaterials(material(101L, ExtractionStatus.SUCCESS));
        givenModelReplies("<<<AI_STRUCTURED>>>\n{\"groups\":[]}");

        service.propose(USER_ID, null, null);

        assertThat(proposalLogLine()).startsWith("연결 제안: trigger=MANUAL, candidates=1");
    }

    @Test
    @DisplayName("자동 호출이 카드로 이어지지 못한 것도 세야 한다 — selectable=0이 로그에 남는다")
    void logsAutoCallThatProducesNothingSelectable() {
        givenUnlinkedMaterials(material(101L, ExtractionStatus.SUCCESS));
        givenModelReplies("<<<AI_STRUCTURED>>>\n{\"groups\":[]}");

        service.propose(USER_ID, null, ProposalTrigger.AUTO);

        // 서버는 "카드가 떴는지"를 판정하지 않는다. trigger=AUTO이고 selectable=0이면
        // 안 뜬 것으로 읽는다 — 프론트의 게이트 규칙을 서버에 복제하지 않는다.
        assertThat(proposalLogLine())
                .isEqualTo("연결 제안: trigger=AUTO, candidates=1, groups=1, selectable=0");
    }

    @Test
    @DisplayName("후보가 없어 모델을 부르지 않은 호출도 같은 줄로 남는다")
    void logsCallsThatEndBeforeTheModel() {
        when(courseMaterialMapper.findAllByUserId(USER_ID)).thenReturn(List.of());
        when(materialLinkMapper.findByUserId(USER_ID)).thenReturn(List.of());

        service.propose(USER_ID, null, ProposalTrigger.AUTO);

        assertThat(proposalLogLine())
                .isEqualTo("연결 제안: trigger=AUTO, status=NO_CANDIDATES, candidates=0");
    }

    @Test
    @DisplayName("자동 호출이 UNAVAILABLE로 끝난 것도 세야 한다 — 분모가 비면 안 된다")
    void logsAutoCallThatEndsUnavailable() {
        givenUnlinkedMaterials(material(101L, ExtractionStatus.SUCCESS));
        when(aiConsultationClient.isConfigured()).thenReturn(false);
        when(courseMapper.findByUserIdAndStatus(eq(USER_ID), anyString())).thenReturn(List.of());

        service.propose(USER_ID, null, ProposalTrigger.AUTO);

        assertThat(proposalLogLine())
                .isEqualTo("연결 제안: trigger=AUTO, status=UNAVAILABLE, candidates=1");
    }
}
