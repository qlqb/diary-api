package com.jungwoo.project.memo.plan.provenance;

import com.jungwoo.project.memo.ai.AiProposalItemMapper;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.UserContextMapper;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.commitment.CommitmentMapper;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.execution.ExecutionItemEventMapper;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.material.CourseMaterialAnalysisMapper;
import com.jungwoo.project.memo.material.MaterialService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.CourseMaterialAnalysis;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.plan.dto.PlanProvenanceResponse;
import com.jungwoo.project.memo.plan.dto.PlanProvenanceResponse.MaterialView;
import com.jungwoo.project.memo.routine.RoutineMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 원본 줄이 나온 자료 파일을 화면에 어떻게 말하는가 — <b>당시 기록</b>과 <b>지금 연결</b>의
 * 구분이 대상이다.
 *
 * <p>여기서 막으려는 실패: 파일이 교체됐는데 "당시 원문"으로 열리는 것, 다른 자료로 다시
 * 연결됐는데 그것이 당시 자료로 보이는 것, 1판 스냅샷(연결 기록 없음)이 지금 연결을 당시
 * 자료라고 단정하는 것, 지워진 파일에 클릭 가능한 링크가 붙는 것.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanProvenanceMaterialViewTest {

    private static final Long USER_ID = 7L;

    @Mock private AiProposalMapper aiProposalMapper;
    @Mock private AiProposalItemMapper aiProposalItemMapper;
    @Mock private CourseMapper courseMapper;
    @Mock private CourseTopicMapper courseTopicMapper;
    @Mock private RoutineMapper routineMapper;
    @Mock private CommitmentMapper commitmentMapper;
    @Mock private ExecutionItemMapper executionItemMapper;
    @Mock private ExecutionItemEventMapper executionItemEventMapper;
    @Mock private UserContextMapper userContextMapper;
    @Mock private CourseMaterialAnalysisMapper analysisMapper;
    @Mock private MaterialService materialService;

    private final PlanProvenanceCodec codec = new PlanProvenanceCodec();
    private PlanProvenanceService service;

    @BeforeEach
    void setUp() {
        service = new PlanProvenanceService(aiProposalMapper, aiProposalItemMapper, codec, courseMapper,
                courseTopicMapper, routineMapper, commitmentMapper, executionItemMapper,
                executionItemEventMapper, userContextMapper, analysisMapper, materialService);
        when(aiProposalItemMapper.findByProposalIdAndUserId(anyLong(), anyLong())).thenReturn(List.of());
    }

    // ===== 2판: 당시 기록이 있다 =====

    @Test
    void recordedMaterialStillTheSame_opensInline_withNoNote() {
        givenTopic(101L, 657L, "2주차");
        givenMaterials(pdf(657L, "네트워크 2주차.pdf", "abc123", MaterialStatus.ACTIVE));
        givenProposalWith(topicSource(101L, recorded(657L, "네트워크 2주차.pdf", "application/pdf", "abc123", "2주차")));

        MaterialView view = viewOfFirstSource();

        assertThat(view.origin()).isEqualTo(MaterialView.MaterialOrigin.RECORDED);
        assertThat(view.state()).isEqualTo(MaterialView.MaterialState.AVAILABLE);
        assertThat(view.openMode()).isEqualTo(MaterialView.MaterialOpenMode.INLINE);
        assertThat(view.materialId()).isEqualTo(657L);
        assertThat(view.filename()).isEqualTo("네트워크 2주차.pdf");
        assertThat(view.locator()).isEqualTo("2주차");
        assertThat(view.note()).isNull();
    }

    /** 같은 id인데 해시가 다르면 파일이 바뀐 것이다. 지금 파일을 열되 당시 원문이라고 말하지 않는다. */
    @Test
    void sameMaterialWithADifferentHash_isChanged_andOpensTheCurrentFileWithASayingSo() {
        givenTopic(101L, 657L, "2주차");
        givenMaterials(pdf(657L, "네트워크 2주차(수정).pdf", "zzz999", MaterialStatus.ACTIVE));
        givenProposalWith(topicSource(101L, recorded(657L, "네트워크 2주차.pdf", "application/pdf", "abc123", "2주차")));

        MaterialView view = viewOfFirstSource();

        assertThat(view.state()).isEqualTo(MaterialView.MaterialState.CHANGED);
        assertThat(view.filename()).as("당시 이름을 유지한다").isEqualTo("네트워크 2주차.pdf");
        assertThat(view.currentFilename()).isEqualTo("네트워크 2주차(수정).pdf");
        assertThat(view.materialId()).isEqualTo(657L);
        assertThat(view.openMode()).isEqualTo(MaterialView.MaterialOpenMode.INLINE);
        assertThat(view.note()).contains("원본이 변경됨");
    }

    /** 학습 항목이 지금은 다른 자료에 연결돼 있다. 당시 자료는 이름으로 남고 현재 파일을 연다. */
    @Test
    void topicRelinkedToAnotherMaterial_showsTheRecordedNameAndOpensTheCurrentOne() {
        givenTopic(101L, 900L, "3주차");
        givenMaterials(pdf(657L, "옛 자료.pdf", "abc123", MaterialStatus.ACTIVE),
                pdf(900L, "새 자료.pdf", "def456", MaterialStatus.ACTIVE));
        givenProposalWith(topicSource(101L, recorded(657L, "옛 자료.pdf", "application/pdf", "abc123", "2주차")));

        MaterialView view = viewOfFirstSource();

        assertThat(view.state()).isEqualTo(MaterialView.MaterialState.RELINKED);
        assertThat(view.filename()).isEqualTo("옛 자료.pdf");
        assertThat(view.currentFilename()).isEqualTo("새 자료.pdf");
        assertThat(view.materialId()).isEqualTo(900L);
        assertThat(view.locator()).as("당시 위치를 유지한다").isEqualTo("2주차");
        assertThat(view.note()).contains("다른 자료가 연결");
    }

    /** 지워진 자료에는 열기 액션이 없다. 이름은 남는다. */
    @Test
    void deletedMaterial_hasNoOpenAction_butKeepsTheName() {
        givenTopic(101L, 657L, "2주차");
        givenMaterials(pdf(657L, "네트워크 2주차.pdf", "abc123", MaterialStatus.DELETED));
        givenProposalWith(topicSource(101L, recorded(657L, "네트워크 2주차.pdf", "application/pdf", "abc123", "2주차")));

        MaterialView view = viewOfFirstSource();

        assertThat(view.state()).isEqualTo(MaterialView.MaterialState.DELETED);
        assertThat(view.materialId()).isNull();
        assertThat(view.openMode()).isEqualTo(MaterialView.MaterialOpenMode.NONE);
        assertThat(view.filename()).isEqualTo("네트워크 2주차.pdf");
        assertThat(view.note()).contains("지워졌어요");
    }

    /** 브라우저가 그리지 못하는 형식은 내려받기다. 라벨이 여는 척하면 안 된다. */
    @Test
    void pptxMaterial_isADownload_notAnInlineOpen() {
        givenTopic(101L, 350L, "1주차");
        CourseMaterial pptx = CourseMaterial.builder().materialId(350L).originalFilename("ch01.pptx")
                .contentType("application/vnd.openxmlformats-officedocument.presentationml.presentation")
                .fileHash("h").status(MaterialStatus.ACTIVE).build();
        givenMaterials(pptx);
        givenProposalWith(topicSource(101L, recorded(350L, "ch01.pptx", pptx.getContentType(), "h", "1주차")));

        assertThat(viewOfFirstSource().openMode()).isEqualTo(MaterialView.MaterialOpenMode.DOWNLOAD);
    }

    // ===== 1판: 당시 기록이 없다 =====

    /** 1판 스냅샷은 연결 기록이 없다. 지금 연결을 열되 "현재 연결된 자료"라고 말한다. */
    @Test
    void version1Snapshot_opensTheCurrentLink_andSaysItIsTheCurrentLink() {
        givenTopic(101L, 657L, "2주차");
        givenMaterials(pdf(657L, "네트워크 2주차.pdf", "abc123", MaterialStatus.ACTIVE));
        givenProposalWith(topicSource(101L, null));

        MaterialView view = viewOfFirstSource();

        assertThat(view.origin()).isEqualTo(MaterialView.MaterialOrigin.CURRENT_LINK);
        assertThat(view.state()).isEqualTo(MaterialView.MaterialState.AVAILABLE);
        assertThat(view.materialId()).isEqualTo(657L);
        assertThat(view.locator()).isEqualTo("2주차");
        assertThat(view.note()).contains("현재 연결된 자료");
    }

    @Test
    void version1Snapshot_withTheTopicGone_hasNoMaterial() {
        when(courseTopicMapper.findByIdAndUserId(anyLong(), anyLong())).thenReturn(null);
        givenMaterials();
        givenProposalWith(topicSource(101L, null));

        assertThat(viewOfFirstSource()).isNull();
    }

    /** 자료에서 읽은 일정(MATERIAL_KEY_DATE)도 분석 행을 거쳐 자료로 이어진다. */
    @Test
    void keyDateSource_resolvesThroughTheAnalysisToItsMaterial() {
        when(analysisMapper.findByIdAndUserId(eq(55L), eq(USER_ID))).thenReturn(
                CourseMaterialAnalysis.builder().analysisId(55L).materialId(657L).build());
        givenMaterials(pdf(657L, "강의계획서.pdf", "abc123", MaterialStatus.ACTIVE));
        givenProposalWith(new ProvidedSource("s1", ProvenanceSourceType.MATERIAL_KEY_DATE, 55L, null, null,
                ProvenanceRepresentation.SELECTED_FIELDS, Map.of("text", "중간고사: 10/20"),
                "- 중간고사: 10/20 [s1]"));

        MaterialView view = viewOfFirstSource();

        assertThat(view).isNotNull();
        assertThat(view.materialId()).isEqualTo(657L);
        assertThat(view.filename()).isEqualTo("강의계획서.pdf");
        assertThat(view.origin()).isEqualTo(MaterialView.MaterialOrigin.CURRENT_LINK);
    }

    /** 회차의 자료는 한 번에 찾는다 — 줄마다 조회하지 않는다. */
    @Test
    void materialsOfAGeneration_areLookedUpOnce() {
        givenTopic(101L, 657L, "2주차");
        givenTopic(102L, 657L, "2주차");
        givenMaterials(pdf(657L, "네트워크 2주차.pdf", "abc123", MaterialStatus.ACTIVE));
        givenProposalWith(
                topicSource(101L, recorded(657L, "네트워크 2주차.pdf", "application/pdf", "abc123", "2주차")),
                topicSource(102L, recorded(657L, "네트워크 2주차.pdf", "application/pdf", "abc123", "2주차")));

        service.forProposal(USER_ID, 1L);

        org.mockito.Mockito.verify(materialService, org.mockito.Mockito.times(1))
                .findForProvenance(eq(USER_ID), any());
    }

    // ===== fixture =====

    private void givenTopic(Long topicId, Long materialId, String locator) {
        when(courseTopicMapper.findByIdAndUserId(eq(topicId), eq(USER_ID))).thenReturn(
                CourseTopic.builder().topicId(topicId).userId(USER_ID).sourceMaterialId(materialId)
                        .sourceLocator(locator).build());
    }

    private void givenMaterials(CourseMaterial... materials) {
        Map<Long, CourseMaterial> byId = java.util.Arrays.stream(materials)
                .collect(Collectors.toMap(CourseMaterial::getMaterialId, m -> m));
        when(materialService.findForProvenance(eq(USER_ID), any())).thenReturn(byId);
    }

    private static CourseMaterial pdf(Long id, String filename, String hash, MaterialStatus status) {
        return CourseMaterial.builder().materialId(id).originalFilename(filename)
                .contentType("application/pdf").fileHash(hash).status(status).build();
    }

    private static ProvidedMaterial recorded(Long id, String filename, String contentType, String hash,
                                             String locator) {
        return new ProvidedMaterial(id, filename, contentType, hash, locator);
    }

    private static ProvidedSource topicSource(Long topicId, ProvidedMaterial material) {
        return new ProvidedSource("s" + topicId, ProvenanceSourceType.TOPIC, topicId, null, null,
                ProvenanceRepresentation.SELECTED_FIELDS,
                Map.of("courseId", 6, "title", "항목 " + topicId, "sourceLocator", "2주차"),
                "  - 항목 " + topicId + " (2주차) [s" + topicId + "]", null, material);
    }

    private void givenProposalWith(ProvidedSource... sources) {
        PlanProvenance snapshot = new PlanProvenance(PlanProvenance.SCHEMA_VERSION, "gen-x",
                LocalDateTime.of(2026, 9, 11, 9, 0), "Asia/Seoul", LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20), "AI", "test-model", List.of(sources), List.of());
        when(aiProposalMapper.findByIdAndUserId(eq(1L), eq(USER_ID))).thenReturn(
                AiProposal.builder().proposalId(1L).userId(USER_ID)
                        .planProvenanceJson(codec.toJson(snapshot)).build());
    }

    private MaterialView viewOfFirstSource() {
        PlanProvenanceResponse response = service.forProposal(USER_ID, 1L);
        assertThat(response.recorded()).isTrue();
        return response.providedSources().get(0).material();
    }
}
