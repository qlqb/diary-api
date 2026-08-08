package com.jungwoo.project.memo.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.dto.TopicDraft;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.CourseMaterialAnalysis;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisStatus;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.dto.MaterialAnalysisResponse;
import org.junit.jupiter.api.Test;
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
    @Mock private CourseMaterialAnalysisMapper analysisMapper;
    @Mock private CourseMapper courseMapper;
    @Mock private AiConsultationClient aiConsultationClient;
    @Mock private AiUsageLimitService aiUsageLimitService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MaterialAnalysisService service;

    private CourseMaterial extractedMaterial() {
        return CourseMaterial.builder()
                .materialId(MATERIAL_ID).userId(USER_ID).courseId(COURSE_ID)
                .materialType(MaterialType.TEXTBOOK_TOC)
                .originalFilename("toc.pdf")
                .extractionStatus(ExtractionStatus.SUCCESS)
                .extractedText("3.2 단순 연결 리스트\n3.3 원형 연결 리스트")
                .build();
    }

    @Test
    void analyze_throwsMaterialExtractionNotReady_whenTextNotExtracted() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(materialService.getOwned(USER_ID, MATERIAL_ID)).thenReturn(
                CourseMaterial.builder().materialId(MATERIAL_ID).courseId(COURSE_ID)
                        .extractionStatus(ExtractionStatus.FAILED_NO_TEXT).build());

        assertThatThrownBy(() -> service.analyze(USER_ID, COURSE_ID, MATERIAL_ID))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MATERIAL_EXTRACTION_NOT_READY));

        verify(analysisMapper, never()).insert(any());
    }

    @Test
    void analyze_savesDraftOnly_neverTouchesCourseTopics() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(materialService.getOwned(USER_ID, MATERIAL_ID)).thenReturn(extractedMaterial());
        when(aiConsultationClient.isConfigured()).thenReturn(true);

        String json = """
                {"summary":"목차를 찾았어요","courseFields":{"textbookTitle":null,"textbookAuthor":null,"textbookPublisher":null,"textbookIsbn":null},"keyDates":[],"topics":[{"title":"단순 연결 리스트","sourceType":"SOURCE","sourceLocator":"3.2절","children":[]}]}
                """;
        when(aiConsultationClient.streamTurn(any(), any(), anyInt()))
                .thenReturn(Flux.just(chatResponse("분석 완료\n<<<AI_STRUCTURED>>>\n" + json)));

        MaterialAnalysisResponse response = service.analyze(USER_ID, COURSE_ID, MATERIAL_ID);

        assertThat(response.getStatus()).isEqualTo(MaterialAnalysisStatus.DRAFT);
        verify(analysisMapper).insert(any());
        // DRAFT 저장만 하고, 확정 topic 트리에는 전혀 손대지 않는다.
        verify(topicService, never()).applyAnalyzedTopics(any(), any(), any(), any());
        verify(courseMapper, never()).updateTextbookInfo(any(), any(), any(), any(), any(), any());
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

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
