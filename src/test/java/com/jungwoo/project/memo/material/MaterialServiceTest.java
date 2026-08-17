package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 자료 저장소의 순서 규칙과 삭제 의미를 검증한다.
 *
 * 여기서 지키려는 것은 두 가지다.
 * 1) 업로드는 파일 -> 추출 -> DB, 삭제는 DB -> 파일. 실패했을 때 남는 것이 항상
 *    "참조되지 않는 고아 파일"이지 "원본 없는 ACTIVE 자료"가 아니어야 한다.
 * 2) 연결 해제는 연결만 끊는다. 자료도 확정된 학습 내용도 건드리지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class MaterialServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long COURSE_ID = 10L;
    private static final Long MATERIAL_ID = 20L;

    @Mock private CourseService courseService;
    @Mock private CourseMapper courseMapper;
    @Mock private CourseMaterialMapper courseMaterialMapper;
    @Mock private MaterialLinkMapper materialLinkMapper;
    @Mock private MaterialTxService materialTxService;
    @Mock private FileStorageService fileStorageService;
    @Mock private TextExtractionService textExtractionService;

    @InjectMocks
    private MaterialService service;

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "syllabus.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }

    @Test
    void upload_writesFileAndExtractsBeforeTouchingDb() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(fileStorageService.store(eq(USER_ID), any()))
                .thenReturn(new FileStorageService.StoredFile("u.pdf", "1/u.pdf", "pdf", "abc123"));
        when(textExtractionService.extract(any(), eq("pdf")))
                .thenReturn(new TextExtractionService.ExtractionResult(ExtractionStatus.SUCCESS, "본문", null));

        service.upload(USER_ID, COURSE_ID, MaterialType.SYLLABUS, pdf());

        // DB 쓰기가 마지막이어야 한다. 이 순서가 뒤집히면 커밋 실패 시
        // "status=ACTIVE인데 원본 파일이 없는 자료"가 남는다.
        InOrder order = inOrder(fileStorageService, textExtractionService, materialTxService);
        order.verify(fileStorageService).store(eq(USER_ID), any());
        order.verify(textExtractionService).extract(any(), eq("pdf"));
        order.verify(materialTxService).createWithLink(any(), eq(COURSE_ID), eq(MaterialType.SYLLABUS));
    }

    @Test
    void upload_persistsExtractionResultAndHashInSingleInsert() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(fileStorageService.store(eq(USER_ID), any()))
                .thenReturn(new FileStorageService.StoredFile("u.pdf", "1/u.pdf", "pdf", "abc123"));
        when(textExtractionService.extract(any(), eq("pdf")))
                .thenReturn(new TextExtractionService.ExtractionResult(ExtractionStatus.SUCCESS, "본문", null));

        service.upload(USER_ID, COURSE_ID, MaterialType.SYLLABUS, pdf());

        ArgumentCaptor<CourseMaterial> captor = ArgumentCaptor.forClass(CourseMaterial.class);
        verify(materialTxService).createWithLink(captor.capture(), any(), any());
        CourseMaterial saved = captor.getValue();
        // 추출을 트랜잭션 앞으로 옮겼으므로 INSERT 한 번에 결과까지 다 들어간다.
        assertThat(saved.getExtractionStatus()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(saved.getExtractedText()).isEqualTo("본문");
        assertThat(saved.getStoragePath()).isEqualTo("1/u.pdf");
        assertThat(saved.getFileHash()).isEqualTo("abc123");
    }

    @Test
    void uploadWithoutCourse_skipsCourseOwnershipCheckAndCreatesNoLink() {
        when(fileStorageService.store(eq(USER_ID), any()))
                .thenReturn(new FileStorageService.StoredFile("u.pdf", "1/u.pdf", "pdf", "abc123"));
        when(textExtractionService.extract(any(), eq("pdf")))
                .thenReturn(new TextExtractionService.ExtractionResult(ExtractionStatus.SUCCESS, "본문", null));

        // 전역 자료함 업로드: 프로젝트도 materialType도 없다. 그게 정상 상태다.
        service.upload(USER_ID, null, null, pdf());

        verify(courseService, never()).getOwned(any(), any());
        verify(materialTxService).createWithLink(any(), eq(null), eq(null));
    }

    @Test
    void delete_commitsDbChangeBeforeRemovingFile() {
        CourseMaterial material = CourseMaterial.builder()
                .materialId(MATERIAL_ID).userId(USER_ID).storagePath("1/u.pdf").build();
        when(materialTxService.markDeleted(USER_ID, MATERIAL_ID)).thenReturn(material);

        service.delete(USER_ID, MATERIAL_ID);

        // markDeleted는 다른 빈이라 호출이 끝나는 순간 커밋된다. 파일은 그 뒤에 지운다 —
        // 반대로 하면 커밋 실패 시 원본만 사라진 ACTIVE 자료가 남는다.
        InOrder order = inOrder(materialTxService, fileStorageService);
        order.verify(materialTxService).markDeleted(USER_ID, MATERIAL_ID);
        order.verify(fileStorageService).deleteQuietly(MATERIAL_ID, "1/u.pdf");
    }

    @Test
    void removeLink_deletesOnlyTheLink_leavingMaterialAndConfirmedStateIntact() {
        when(materialLinkMapper.findByMaterialIdAndCourseIdAndUserId(MATERIAL_ID, COURSE_ID, USER_ID))
                .thenReturn(MaterialLink.builder().materialId(MATERIAL_ID).courseId(COURSE_ID).build());

        service.removeLink(USER_ID, MATERIAL_ID, COURSE_ID);

        verify(materialLinkMapper).delete(MATERIAL_ID, COURSE_ID, USER_ID);
        // 연결을 끊었다고 자료 원본을 지우거나 삭제 표시하지 않는다.
        verify(materialLinkMapper, never()).deleteAllByMaterialId(any(), any());
        verify(courseMaterialMapper, never()).markDeleted(any(), any());
        verify(fileStorageService, never()).deleteQuietly(any(), any());
    }

    @Test
    void removeLink_throwsWhenNotLinked() {
        when(materialLinkMapper.findByMaterialIdAndCourseIdAndUserId(MATERIAL_ID, COURSE_ID, USER_ID))
                .thenReturn(null);

        assertThatThrownBy(() -> service.removeLink(USER_ID, MATERIAL_ID, COURSE_ID))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE));

        verify(materialLinkMapper, never()).delete(any(), any(), any());
    }

    @Test
    void addLink_setsMaterialTypeOnTheLinkNotTheMaterial() {
        when(courseMaterialMapper.findByIdAndUserId(MATERIAL_ID, USER_ID))
                .thenReturn(CourseMaterial.builder().materialId(MATERIAL_ID).build());
        when(courseService.getOwned(USER_ID, COURSE_ID))
                .thenReturn(Course.builder().courseId(COURSE_ID).title("자료구조").build());
        when(materialLinkMapper.findByMaterialIdAndCourseIdAndUserId(MATERIAL_ID, COURSE_ID, USER_ID))
                .thenReturn(null);

        var response = service.addLink(USER_ID, MATERIAL_ID, COURSE_ID, MaterialType.OTHER);

        ArgumentCaptor<MaterialLink> captor = ArgumentCaptor.forClass(MaterialLink.class);
        verify(materialLinkMapper).insert(captor.capture());
        assertThat(captor.getValue().getMaterialType()).isEqualTo(MaterialType.OTHER);
        assertThat(response.getCourseTitle()).isEqualTo("자료구조");
    }

    @Test
    void addLink_rejectsWhenCourseIsArchived() {
        when(courseMaterialMapper.findByIdAndUserId(MATERIAL_ID, USER_ID))
                .thenReturn(CourseMaterial.builder().materialId(MATERIAL_ID).build());
        when(courseService.getOwned(USER_ID, COURSE_ID))
                .thenReturn(Course.builder().courseId(COURSE_ID).status(CourseStatus.ARCHIVED).build());

        // 후보 목록(GET /courses)은 이미 ACTIVE만 돌려주지만, courseId를 직접 지정해 호출하는
        // 경로까지 막아야 "보관한 프로젝트에는 새로 연결할 수 없다"가 실제로 지켜진다.
        assertThatThrownBy(() -> service.addLink(USER_ID, MATERIAL_ID, COURSE_ID, MaterialType.OTHER))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COURSE_ARCHIVED));

        verify(materialLinkMapper, never()).findByMaterialIdAndCourseIdAndUserId(any(), any(), any());
        verify(materialLinkMapper, never()).insert(any());
    }

    @Test
    void addLink_rejectsDuplicateLinkToSameCourse() {
        when(courseMaterialMapper.findByIdAndUserId(MATERIAL_ID, USER_ID))
                .thenReturn(CourseMaterial.builder().materialId(MATERIAL_ID).build());
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(materialLinkMapper.findByMaterialIdAndCourseIdAndUserId(MATERIAL_ID, COURSE_ID, USER_ID))
                .thenReturn(MaterialLink.builder().materialId(MATERIAL_ID).courseId(COURSE_ID).build());

        assertThatThrownBy(() -> service.addLink(USER_ID, MATERIAL_ID, COURSE_ID, MaterialType.OTHER))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MATERIAL_ALREADY_LINKED_TO_COURSE));

        verify(materialLinkMapper, never()).insert(any());
    }

    @Test
    void getActiveOwned_doesNotReturnDeletedMaterial() {
        when(courseMaterialMapper.findByIdAndUserId(MATERIAL_ID, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.getActiveOwned(USER_ID, MATERIAL_ID))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COURSE_MATERIAL_NOT_FOUND));
    }
}
