package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.dto.MaterialResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 학습 자료 업로드 오케스트레이션: 소유권 검증 -> 디스크 저장 -> 텍스트 추출 -> 메타데이터 저장.
 *
 * Material Agent 분석은 이 서비스가 만든 CourseMaterial(extractedText)을 입력으로만 쓰고,
 * 여기서는 AI를 호출하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialService {

    private final CourseService courseService;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialLinkMapper materialLinkMapper;
    private final MaterialTxService materialTxService;
    private final FileStorageService fileStorageService;
    private final TextExtractionService textExtractionService;

    /**
     * 업로드 순서가 중요하다: 파일 저장 -> 텍스트 추출 -> [트랜잭션: material + link INSERT].
     *
     * DB 쓰기를 마지막에 두면 실패했을 때 남는 것은 "아무도 참조하지 않는 디스크 파일"뿐이고,
     * 이건 로그만 남기면 되는 무해한 상태다. 반대로 INSERT를 먼저 커밋하고 파일을 쓰면
     * "status=ACTIVE인데 원본이 없는 자료"가 남는데, 그건 삭제 규칙이 금지하는 상태다.
     * (삭제는 정반대로 DB 먼저다 — 양쪽 다 실패 시 고아 파일이 남는 쪽으로 통일한다.)
     *
     * 추출을 트랜잭션 앞에 두면 extraction 결과를 처음부터 알고 INSERT하므로 별도 UPDATE가
     * 필요 없다.
     */
    public MaterialResponse upload(Long userId, Long courseId, MaterialType materialType, MultipartFile file) {
        if (courseId != null) {
            courseService.getOwned(userId, courseId);
        }

        FileStorageService.StoredFile stored = fileStorageService.store(userId, file);

        Path savedPath = fileStorageService.resolve(stored.storagePath());
        TextExtractionService.ExtractionResult result = textExtractionService.extract(savedPath, stored.extension());

        CourseMaterial material = CourseMaterial.builder()
                .userId(userId)
                .originalFilename(sanitizeDisplayName(file.getOriginalFilename()))
                .storedFilename(stored.storedFilename())
                .storagePath(stored.storagePath())
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .fileHash(stored.fileHash())
                .extractionStatus(result.status())
                .extractedText(result.text())
                .extractionError(result.error())
                .status(MaterialStatus.ACTIVE)
                .build();
        materialTxService.createWithLink(material, courseId, materialType);

        log.info("자료 업로드 완료: userId={}, courseId={}, materialId={}, extractionStatus={}",
                userId, courseId, material.getMaterialId(), result.status());

        return MaterialResponse.of(material, courseId, materialType);
    }

    @Transactional(readOnly = true)
    public List<MaterialResponse> listByCourse(Long userId, Long courseId) {
        courseService.getOwned(userId, courseId);
        // materialType은 자료가 아니라 링크가 갖는다 — 같은 파일이 프로젝트마다 다른 성격일 수 있다.
        Map<Long, MaterialType> typeByMaterialId = materialLinkMapper.findByCourseIdAndUserId(courseId, userId)
                .stream()
                .collect(Collectors.toMap(MaterialLink::getMaterialId, MaterialLink::getMaterialType,
                        (a, b) -> a));
        return courseMaterialMapper.findByCourseIdAndUserId(courseId, userId).stream()
                .map(m -> MaterialResponse.of(m, courseId, typeByMaterialId.get(m.getMaterialId())))
                .toList();
    }

    /** ACTIVE 자료만. 목록·상세·업로드 후속·분석·AI·링크 생성 — 기본 경로 전부 이걸 쓴다. */
    @Transactional(readOnly = true)
    public CourseMaterial getActiveOwned(Long userId, Long materialId) {
        CourseMaterial material = courseMaterialMapper.findByIdAndUserId(materialId, userId);
        if (material == null) {
            throw new NotFoundException(ErrorCode.COURSE_MATERIAL_NOT_FOUND);
        }
        return material;
    }

    /** DELETED도 반환한다. provenance 표시("원본 삭제됨 · 파일명") 전용 — 그 외 경로에서 쓰지 않는다. */
    @Transactional(readOnly = true)
    public CourseMaterial getOwnedIncludingDeleted(Long userId, Long materialId) {
        CourseMaterial material = courseMaterialMapper.findByIdAndUserIdIncludingDeleted(materialId, userId);
        if (material == null) {
            throw new NotFoundException(ErrorCode.COURSE_MATERIAL_NOT_FOUND);
        }
        return material;
    }

    /**
     * 이 자료가 이 프로젝트에 실제로 연결되어 있는지 확인하고 링크를 돌려준다.
     *
     * 자료가 course에 종속돼 있을 때는 소유권 검증만으로 충분했지만, 전역이 되는 순간
     * 연결되지 않은 자료를 임의의 프로젝트 맥락에서 다룰 수 있는 구멍이 생긴다.
     */
    @Transactional(readOnly = true)
    public MaterialLink getRequiredLink(Long userId, Long materialId, Long courseId) {
        MaterialLink link = materialLinkMapper.findByMaterialIdAndCourseIdAndUserId(materialId, courseId, userId);
        if (link == null) {
            throw new NotFoundException(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE);
        }
        return link;
    }

    /** 경로 조작 문자를 제거한 표시용 원본 파일명. 실제 저장 경로에는 쓰지 않는다(storagePath만 사용). */
    private String sanitizeDisplayName(String originalFilename) {
        if (originalFilename == null) {
            return "unknown";
        }
        String name = originalFilename.replaceAll("[\\\\/\\x00]", "_");
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
