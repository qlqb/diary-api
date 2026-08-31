package com.jungwoo.project.memo.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.CourseNoteService;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.dto.CourseNoteDraft;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.dto.TopicDraft;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.CourseMaterialAnalysis;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisStatus;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.dto.MaterialAnalysisResponse;
import com.jungwoo.project.memo.material.dto.MaterialAnalysisStartResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Material Agent: analyze()가 DRAFT만 만들고 course_topics에 전혀 손대지 않는지, apply()가
 * DRAFT일 때만 동작하며 source/AI_DERIVED provenance를 그대로 넘기는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MaterialAnalysisServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long COURSE_ID = 10L;
    private static final Long MATERIAL_ID = 20L;
    private static final Long ANALYSIS_ID = 30L;

    @Mock private CourseService courseService;
    @Mock private MaterialService materialService;
    @Mock private TopicService topicService;
    @Mock private CourseNoteService courseNoteService;
    @Mock private CourseMaterialAnalysisMapper analysisMapper;
    @Mock private CourseMapper courseMapper;
    @Mock private AiConsultationClient aiConsultationClient;
    @Mock private AiUsageLimitService aiUsageLimitService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MaterialAnalysisService service;

    /**
     * ★ @InjectMocks는 @Value 필드를 주입하지 않는다. 그래서 Java 기본값이 없는 필드는
     * 0/null이 되고, requestTimeoutSeconds = 0이면 Duration.ofSeconds(0)이라 Flux가
     * 즉시 타임아웃한다 — 목이 얼마나 빨리 emit하는지에 따라 통과/실패가 갈리는
     * flaky 테스트가 된다(단독 실행은 통과, 전체 실행은 실패).
     *
     * 지금은 서비스의 @Value 필드가 애노테이션의 프로퍼티 기본값을 Java 초기값으로도
     * 미러링하므로 이 세팅이 없어도 동작한다. 그래도 여기서 명시하는 이유는, 이 테스트가
     * 타임아웃 값에 의존한다는 사실을 보이게 두고 나중에 미러링이 지워져도 여기서
     * 막히게 하려는 것이다.
     */
    @BeforeEach
    void setUpValueFields() {
        ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 90);
        ReflectionTestUtils.setField(service, "maxInputTokens", 12000);
        ReflectionTestUtils.setField(service, "maxCompletionTokens", 4000);
        ReflectionTestUtils.setField(service, "modelName", "test-model");
    }

    private CourseMaterial extractedMaterial() {
        return CourseMaterial.builder()
                .materialId(MATERIAL_ID).userId(USER_ID)
                .originalFilename("toc.pdf")
                .extractionStatus(ExtractionStatus.SUCCESS)
                .extractedText("3.2 단순 연결 리스트\n3.3 원형 연결 리스트")
                .build();
    }

    /** 자료 성격은 이제 자료가 아니라 이 프로젝트의 링크가 갖는다. */
    private MaterialLink link(MaterialType type) {
        return MaterialLink.builder()
                .userId(USER_ID).materialId(MATERIAL_ID).courseId(COURSE_ID)
                .materialType(type)
                .build();
    }

    private CourseMaterialAnalysis analysis(Long courseId) {
        return CourseMaterialAnalysis.builder()
                .analysisId(ANALYSIS_ID).userId(USER_ID).courseId(courseId).materialId(MATERIAL_ID)
                .status(MaterialAnalysisStatus.APPLIED)
                .analysisJson("{}")
                .build();
    }

    @Test
    void analyze_throwsMaterialExtractionNotReady_whenTextNotExtracted() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(materialService.getActiveOwned(USER_ID, MATERIAL_ID)).thenReturn(
                CourseMaterial.builder().materialId(MATERIAL_ID)
                        .extractionStatus(ExtractionStatus.FAILED_NO_TEXT).build());

        assertThatThrownBy(() -> service.analyze(USER_ID, COURSE_ID, MATERIAL_ID))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MATERIAL_EXTRACTION_NOT_READY));

        verify(analysisMapper, never()).insert(any());
    }

    @Test
    void analyze_throwsMaterialNotLinked_whenMaterialIsNotConnectedToThisCourse() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(materialService.getActiveOwned(USER_ID, MATERIAL_ID)).thenReturn(extractedMaterial());
        when(materialService.getRequiredLink(USER_ID, MATERIAL_ID, COURSE_ID))
                .thenThrow(new NotFoundException(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE));

        // 자료를 소유하고 있어도, 이 프로젝트에 연결하지 않았다면 그 맥락에서 분석할 수 없다.
        assertThatThrownBy(() -> service.analyze(USER_ID, COURSE_ID, MATERIAL_ID))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE));

        verify(analysisMapper, never()).insert(any());
    }

    @Test
    void analyze_savesDraftOnly_neverTouchesCourseTopics() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(materialService.getActiveOwned(USER_ID, MATERIAL_ID)).thenReturn(extractedMaterial());
        when(materialService.getRequiredLink(USER_ID, MATERIAL_ID, COURSE_ID))
                .thenReturn(link(MaterialType.TEXTBOOK_TOC));
        when(aiConsultationClient.isConfigured()).thenReturn(true);

        String json = """
                {"summary":"목차를 찾았어요","courseFields":{"textbookTitle":null,"textbookAuthor":null,"textbookPublisher":null,"textbookIsbn":null},"keyDates":[],"topics":[{"title":"단순 연결 리스트","sourceType":"SOURCE","sourceLocator":"3.2절","children":[]}]}
                """;
        when(aiConsultationClient.streamTurn(any(), any(), anyInt()))
                .thenReturn(Flux.just(chatResponse("분석 완료\n<<<AI_STRUCTURED>>>\n" + json)));

        MaterialAnalysisStartResult result = service.analyze(USER_ID, COURSE_ID, MATERIAL_ID);

        assertThat(result.analysis().getStatus()).isEqualTo(MaterialAnalysisStatus.DRAFT);
        assertThat(result.created()).isTrue();
        verify(analysisMapper).insert(any());
        // DRAFT 저장만 하고, 확정 topic 트리에는 전혀 손대지 않는다.
        verify(topicService, never()).applyAnalyzedTopics(any(), any(), any(), any());
        verify(courseMapper, never()).updateTextbookInfo(any(), any(), any(), any(), any(), any());
    }

    // ===== 열린 DRAFT는 맥락당 하나 =====

    /**
     * 검토 중이던 DRAFT가 있으면 다시 분석하지 않고 그것을 돌려준다. 화면이 새로고침되면
     * 검토 폼이 사라져 사용자가 같은 버튼을 다시 누르는데, 그때마다 AI를 부르고 DRAFT를
     * 쌓던 것이 원래 문제였다.
     */
    @Test
    void analyze_reusesOpenDraft_withoutCallingAi() {
        givenOwnedAndLinked();
        when(analysisMapper.findLatestDraftByContext(USER_ID, COURSE_ID, MATERIAL_ID))
                .thenReturn(openDraft());

        MaterialAnalysisStartResult result = service.analyze(USER_ID, COURSE_ID, MATERIAL_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.analysis().getAnalysisId()).isEqualTo(ANALYSIS_ID);
        assertThat(result.analysis().getStatus()).isEqualTo(MaterialAnalysisStatus.DRAFT);
    }

    /**
     * 재사용 경로는 AI 쪽을 하나도 건드리지 않아야 한다. 사용량 한도를 깎거나 "AI 미설정"으로
     * 막히면, 이미 만들어 둔 초안을 못 꺼내 보는 상태가 된다.
     */
    @Test
    void analyze_reusingDraft_skipsAiConfigUsageAndInsert() {
        givenOwnedAndLinked();
        when(analysisMapper.findLatestDraftByContext(USER_ID, COURSE_ID, MATERIAL_ID))
                .thenReturn(openDraft());

        service.analyze(USER_ID, COURSE_ID, MATERIAL_ID);

        verify(aiConsultationClient, never()).isConfigured();
        verify(aiUsageLimitService, never()).checkLimit(any());
        verify(aiConsultationClient, never()).streamTurn(any(), any(), anyInt());
        verify(analysisMapper, never()).insert(any());
        verify(topicService, never()).applyAnalyzedTopics(any(), any(), any(), any());
    }

    /** 재사용이든 아니든 소유권·연결 검증은 건너뛰지 않는다. 남의 맥락의 초안이 나오면 안 된다. */
    @Test
    void analyze_looksUpDraftScopedToUserCourseAndMaterial() {
        givenOwnedAndLinked();
        when(analysisMapper.findLatestDraftByContext(USER_ID, COURSE_ID, MATERIAL_ID))
                .thenReturn(openDraft());

        service.analyze(USER_ID, COURSE_ID, MATERIAL_ID);

        verify(courseService).getOwned(USER_ID, COURSE_ID);
        verify(materialService).getRequiredLink(USER_ID, MATERIAL_ID, COURSE_ID);
        verify(analysisMapper).findLatestDraftByContext(USER_ID, COURSE_ID, MATERIAL_ID);
    }

    /**
     * 사전 조회는 경쟁을 못 막는다 — 두 요청이 동시에 "없음"을 읽으면 둘 다 INSERT까지 온다.
     * 늦게 도착한 쪽은 유일 인덱스에 걸리고, 그때는 이긴 쪽의 DRAFT를 돌려준다.
     */
    @Test
    void analyze_returnsWinningDraft_whenInsertLosesUniqueRace() {
        givenAiProducesDraft();
        when(analysisMapper.findLatestDraftByContext(USER_ID, COURSE_ID, MATERIAL_ID))
                .thenReturn(null)          // 사전 조회: 아직 없다
                .thenReturn(openDraft());  // 충돌 후 재조회: 상대가 만든 것이 보인다
        doThrow(new DuplicateKeyException("uq_course_material_analyses_single_draft"))
                .when(analysisMapper).insert(any());

        MaterialAnalysisStartResult result = service.analyze(USER_ID, COURSE_ID, MATERIAL_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.analysis().getAnalysisId()).isEqualTo(ANALYSIS_ID);
    }

    /** 유일 키 위반인데 DRAFT가 없으면 우리가 아는 그 제약이 아니다. 삼키면 원인이 사라진다. */
    @Test
    void analyze_rethrows_whenDuplicateKeyButNoDraftFound() {
        givenAiProducesDraft();
        when(analysisMapper.findLatestDraftByContext(USER_ID, COURSE_ID, MATERIAL_ID)).thenReturn(null);
        doThrow(new DuplicateKeyException("다른 제약")).when(analysisMapper).insert(any());

        assertThatThrownBy(() -> service.analyze(USER_ID, COURSE_ID, MATERIAL_ID))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("다른 제약");
    }

    private void givenOwnedAndLinked() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(materialService.getActiveOwned(USER_ID, MATERIAL_ID)).thenReturn(extractedMaterial());
        when(materialService.getRequiredLink(USER_ID, MATERIAL_ID, COURSE_ID))
                .thenReturn(link(MaterialType.TEXTBOOK_TOC));
    }

    private CourseMaterialAnalysis openDraft() {
        return CourseMaterialAnalysis.builder()
                .analysisId(ANALYSIS_ID).userId(USER_ID).courseId(COURSE_ID).materialId(MATERIAL_ID)
                .status(MaterialAnalysisStatus.DRAFT)
                .analysisJson("{}")
                .build();
    }

    /** AI가 정상 응답해 INSERT 직전까지 가는 상태를 만든다. */
    private void givenAiProducesDraft() {
        givenOwnedAndLinked();
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        String json = """
                {"summary":"목차를 찾았어요","courseFields":{"textbookTitle":null,"textbookAuthor":null,"textbookPublisher":null,"textbookIsbn":null},"keyDates":[],"topics":[]}
                """;
        when(aiConsultationClient.streamTurn(any(), any(), anyInt()))
                .thenReturn(Flux.just(chatResponse("분석 완료\n<<<AI_STRUCTURED>>>\n" + json)));
    }

    @Test
    void listByMaterialInCourse_throwsMaterialNotLinked_ratherThanReturningEmptyList() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(materialService.getActiveOwned(USER_ID, MATERIAL_ID)).thenReturn(extractedMaterial());
        when(materialService.getRequiredLink(USER_ID, MATERIAL_ID, COURSE_ID))
                .thenThrow(new NotFoundException(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE));

        // 빈 배열 200은 "분석이 없다"로 읽힌다. 실제로는 "이 자료는 이 프로젝트 것이 아니다"이므로
        // analyze()/apply()와 같은 검증 순서를 밟아 같은 에러로 막는다.
        assertThatThrownBy(() -> service.listByMaterialInCourse(USER_ID, COURSE_ID, MATERIAL_ID))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE));

        verify(analysisMapper, never()).findByMaterialIdAndCourseIdAndUserId(any(), any(), any());
        verify(analysisMapper, never()).findByMaterialIdAndUserId(any(), any());
    }

    @Test
    void listByMaterialInCourse_readsOnlyThisCourseContext() {
        when(courseService.getOwned(USER_ID, COURSE_ID))
                .thenReturn(Course.builder().courseId(COURSE_ID).title("자료구조").build());
        when(materialService.getActiveOwned(USER_ID, MATERIAL_ID)).thenReturn(extractedMaterial());
        when(materialService.getRequiredLink(USER_ID, MATERIAL_ID, COURSE_ID))
                .thenReturn(link(MaterialType.SYLLABUS));
        when(analysisMapper.findByMaterialIdAndCourseIdAndUserId(MATERIAL_ID, COURSE_ID, USER_ID))
                .thenReturn(List.of(analysis(COURSE_ID)));

        List<MaterialAnalysisResponse> result = service.listByMaterialInCourse(USER_ID, COURSE_ID, MATERIAL_ID);

        // 같은 자료가 다른 프로젝트에도 걸려 있어도 그쪽 해석은 섞이지 않는다.
        assertThat(result).singleElement()
                .satisfies(r -> assertThat(r.getCourseId()).isEqualTo(COURSE_ID));
        verify(analysisMapper, never()).findByMaterialIdAndUserId(any(), any());
    }

    @Test
    void listByMaterial_returnsEveryContextWithProjectTitleAttached() {
        Long otherCourseId = 11L;
        when(materialService.getActiveOwned(USER_ID, MATERIAL_ID)).thenReturn(extractedMaterial());
        when(analysisMapper.findByMaterialIdAndUserId(MATERIAL_ID, USER_ID))
                .thenReturn(List.of(analysis(COURSE_ID), analysis(otherCourseId)));
        when(courseMapper.findByIdsAndUserId(List.of(COURSE_ID, otherCourseId), USER_ID))
                .thenReturn(List.of(
                        Course.builder().courseId(COURSE_ID).title("자료구조").build(),
                        Course.builder().courseId(otherCourseId).title("빅데이터분석").build()));

        List<MaterialAnalysisResponse> result = service.listByMaterial(USER_ID, MATERIAL_ID);

        // 전역 상세는 맥락을 좁히지 않는 대신 각 행이 어느 프로젝트의 해석인지 말해야 한다 —
        // 프로젝트명이 없으면 "분석 2건"만 보이고 없앤 혼란이 여기서 재발한다.
        assertThat(result).extracting(MaterialAnalysisResponse::getCourseTitle)
                .containsExactly("자료구조", "빅데이터분석");
    }

    @Test
    void apply_rejectsWhenAnalysisNotInDraftStatus() {
        CourseMaterialAnalysis applied = CourseMaterialAnalysis.builder()
                .analysisId(ANALYSIS_ID).userId(USER_ID).courseId(COURSE_ID).materialId(MATERIAL_ID)
                .status(MaterialAnalysisStatus.APPLIED)
                .analysisJson("{}")
                .build();
        when(analysisMapper.findByIdAndUserId(ANALYSIS_ID, USER_ID)).thenReturn(applied);

        assertThatThrownBy(() -> service.apply(USER_ID, ANALYSIS_ID))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MATERIAL_ANALYSIS_NOT_DRAFT));

        verify(topicService, never()).applyAnalyzedTopics(any(), any(), any(), any());
    }

    @Test
    void apply_rejectsWhenLinkWasRemoved_leavingAnalysisRecordIntact() {
        CourseMaterialAnalysis draft = CourseMaterialAnalysis.builder()
                .analysisId(ANALYSIS_ID).userId(USER_ID).courseId(COURSE_ID).materialId(MATERIAL_ID)
                .status(MaterialAnalysisStatus.DRAFT)
                .analysisJson("{}")
                .build();
        when(analysisMapper.findByIdAndUserId(ANALYSIS_ID, USER_ID)).thenReturn(draft);
        when(materialService.getRequiredLink(USER_ID, MATERIAL_ID, COURSE_ID))
                .thenThrow(new NotFoundException(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE));

        // 연결을 끊은 뒤에는 그 draft를 적용할 수 없다. 분석 레코드 자체는 지우지 않고
        // (조회는 계속 되고 다시 연결하면 되살아난다) 적용만 막는다.
        assertThatThrownBy(() -> service.apply(USER_ID, ANALYSIS_ID))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE));

        verify(topicService, never()).applyAnalyzedTopics(any(), any(), any(), any());
        verify(analysisMapper, never()).updateStatus(any(), any(), any());
    }

    @Test
    void apply_confirmsTopicsWithProvenancePreserved_whenDraftIsApplied() {
        String json = """
                {"summary":"확인","courseFields":{"textbookTitle":"자료구조","textbookAuthor":null,"textbookPublisher":null,"textbookIsbn":null},"keyDates":[],
                 "topics":[{"title":"연결 리스트","sourceType":"SOURCE","sourceLocator":"3장","children":[
                    {"title":"노드 구조","sourceType":"AI_DERIVED","sourceLocator":null,"children":[]}
                 ]}]}
                """;
        CourseMaterialAnalysis draft = CourseMaterialAnalysis.builder()
                .analysisId(ANALYSIS_ID).userId(USER_ID).courseId(COURSE_ID).materialId(MATERIAL_ID)
                .status(MaterialAnalysisStatus.DRAFT)
                .analysisJson(json)
                .build();
        when(analysisMapper.findByIdAndUserId(ANALYSIS_ID, USER_ID)).thenReturn(draft);
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(topicService.applyAnalyzedTopics(eq(USER_ID), eq(COURSE_ID), eq(MATERIAL_ID), any())).thenReturn(2);

        MaterialAnalysisResponse response = service.apply(USER_ID, ANALYSIS_ID);

        assertThat(response.getStatus()).isEqualTo(MaterialAnalysisStatus.APPLIED);
        assertThat(response.getCreatedTopicCount()).isEqualTo(2);

        verify(courseMapper).updateTextbookInfo(COURSE_ID, USER_ID, "자료구조", null, null, null);

        ArgumentCaptor<List<TopicDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(topicService).applyAnalyzedTopics(eq(USER_ID), eq(COURSE_ID), eq(MATERIAL_ID), captor.capture());
        TopicDraft root = captor.getValue().get(0);
        assertThat(root.sourceType()).isEqualTo("SOURCE");
        assertThat(root.children().get(0).sourceType()).isEqualTo("AI_DERIVED");
    }

    @Test
    void apply_separatesCourseNotesFromTopics_neverMixingNonLearningItemsIntoCourseTopics() {
        String json = """
                {"summary":"확인","courseFields":{"textbookTitle":null,"textbookAuthor":null,"textbookPublisher":null,"textbookIsbn":null},
                 "courseNotes":[
                    {"category":"COURSE_INFO","label":"담당교수","detail":"홍길동 교수, contact@school.ac.kr"},
                    {"category":"ASSESSMENT","label":"평가 비율","detail":"중간 30% · 기말 30% · 과제 20% · 출석 20%"}
                 ],
                 "keyDates":[],
                 "topics":[{"title":"연결 리스트","sourceType":"SOURCE","sourceLocator":"3장","children":[]}]}
                """;
        CourseMaterialAnalysis draft = CourseMaterialAnalysis.builder()
                .analysisId(ANALYSIS_ID).userId(USER_ID).courseId(COURSE_ID).materialId(MATERIAL_ID)
                .status(MaterialAnalysisStatus.DRAFT)
                .analysisJson(json)
                .build();
        when(analysisMapper.findByIdAndUserId(ANALYSIS_ID, USER_ID)).thenReturn(draft);
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(topicService.applyAnalyzedTopics(eq(USER_ID), eq(COURSE_ID), eq(MATERIAL_ID), any())).thenReturn(1);

        service.apply(USER_ID, ANALYSIS_ID);

        // 학습 topic에는 "연결 리스트" 하나만 넘어간다 — 과목 정보/평가 정보는 섞이지 않는다.
        ArgumentCaptor<List<TopicDraft>> topicCaptor = ArgumentCaptor.forClass(List.class);
        verify(topicService).applyAnalyzedTopics(eq(USER_ID), eq(COURSE_ID), eq(MATERIAL_ID), topicCaptor.capture());
        assertThat(topicCaptor.getValue()).hasSize(1);
        assertThat(topicCaptor.getValue().get(0).title()).isEqualTo("연결 리스트");

        // 과목 정보/평가 정보는 course_notes로 따로 저장된다.
        ArgumentCaptor<List<CourseNoteDraft>> noteCaptor = ArgumentCaptor.forClass(List.class);
        verify(courseNoteService).saveAll(eq(USER_ID), eq(COURSE_ID), eq(MATERIAL_ID), noteCaptor.capture());
        assertThat(noteCaptor.getValue()).hasSize(2);
        assertThat(noteCaptor.getValue().get(0).category()).isEqualTo("COURSE_INFO");
        assertThat(noteCaptor.getValue().get(0).label()).isEqualTo("담당교수");
        assertThat(noteCaptor.getValue().get(1).category()).isEqualTo("ASSESSMENT");
    }

    @Test
    void apply_toleratesMissingCourseNotesField_treatingItAsEmpty() {
        String json = """
                {"summary":"확인","courseFields":{"textbookTitle":null,"textbookAuthor":null,"textbookPublisher":null,"textbookIsbn":null},"keyDates":[],
                 "topics":[{"title":"연결 리스트","sourceType":"SOURCE","sourceLocator":"3장","children":[]}]}
                """;
        CourseMaterialAnalysis draft = CourseMaterialAnalysis.builder()
                .analysisId(ANALYSIS_ID).userId(USER_ID).courseId(COURSE_ID).materialId(MATERIAL_ID)
                .status(MaterialAnalysisStatus.DRAFT)
                .analysisJson(json)
                .build();
        when(analysisMapper.findByIdAndUserId(ANALYSIS_ID, USER_ID)).thenReturn(draft);
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(topicService.applyAnalyzedTopics(eq(USER_ID), eq(COURSE_ID), eq(MATERIAL_ID), any())).thenReturn(1);

        service.apply(USER_ID, ANALYSIS_ID);

        verify(courseNoteService).saveAll(eq(USER_ID), eq(COURSE_ID), eq(MATERIAL_ID), eq(List.of()));
    }

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
