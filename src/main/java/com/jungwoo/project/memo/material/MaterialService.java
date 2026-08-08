package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.dto.MaterialResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

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
    private final FileStorageService fileStorageService;
    private final TextExtractionService textExtractionService;

    public MaterialResponse upload(Long userId, Long courseId, MaterialType materialType, MultipartFile file) {
        courseService.getOwned(userId, courseId);

        FileStorageService.StoredFile stored = fileStorageService.store(userId, courseId, file);

        CourseMaterial material = CourseMaterial.builder()
                .userId(userId)
                .courseId(courseId)
                .materialType(materialType)
                .originalFilename(sanitizeDisplayName(file.getOriginalFilename()))
                .storedFilename(stored.storedFilename())
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .extractionStatus(ExtractionStatus.PENDING)
                .build();
        courseMaterialMapper.insert(material);

        Path savedPath = fileStorageService.resolve(userId, courseId, stored.storedFilename());
        TextExtractionService.ExtractionResult result = textExtractionService.extract(savedPath, stored.extension());
        courseMaterialMapper.updateExtractionResult(
                material.getMaterialId(), result.status().name(), result.text(), result.error());

        material.setExtractionStatus(result.status());
        material.setExtractedText(result.text());
        material.setExtractionError(result.error());

        log.info("자료 업로드 완료: userId={}, courseId={}, materialId={}, extractionStatus={}",
                userId, courseId, material.getMaterialId(), result.status());

        return MaterialResponse.of(material);
    }

    @Transactional(readOnly = true)
    public List<MaterialResponse> listByCourse(Long userId, Long courseId) {
        courseService.getOwned(userId, courseId);
        return courseMaterialMapper.findByCourseIdAndUserId(courseId, userId).stream()
                .map(MaterialResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public CourseMaterial getOwned(Long userId, Long materialId) {
        CourseMaterial material = courseMaterialMapper.findByIdAndUserId(materialId, userId);
        if (material == null) {
            throw new NotFoundException(ErrorCode.COURSE_MATERIAL_NOT_FOUND);
        }
        return material;
    }

    /** 경로 조작 문자를 제거한 표시용 원본 파일명. 실제 저장 경로에는 쓰지 않는다(storedFilename만 사용). */
    private String sanitizeDisplayName(String originalFilename) {
        if (originalFilename == null) {
            return "unknown";
        }
        String name = originalFilename.replaceAll("[\\\\/\\x00]", "_");
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
