package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자료 관련 DB 쓰기의 트랜잭션 경계만 담당한다.
 *
 * MaterialService에서 분리한 이유는 순전히 Spring 프록시 때문이다 — 같은 클래스 안에서
 * this.method()를 부르면 프록시를 타지 않아 @Transactional이 아예 적용되지 않는다.
 * 파일 I/O를 트랜잭션 밖에 두면서 DB 쓰기만 원자적으로 묶으려면 반드시 다른 빈이어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialTxService {

    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialLinkMapper materialLinkMapper;

    /**
     * 자료 원본과 (courseId가 주어졌으면) 그 프로젝트 연결을 한 트랜잭션에 만든다.
     *
     * courseId가 null이면 링크 없이 자료만 만든다 — 전역 자료함에서 프로젝트 없이 올린 경우다.
     * 그때 materialType은 아직 정해지지 않는다(연결 시점에 정해진다).
     */
    @Transactional
    public CourseMaterial createWithLink(CourseMaterial material, Long courseId, MaterialType materialType) {
        courseMaterialMapper.insert(material);
        if (courseId != null) {
            materialLinkMapper.insert(MaterialLink.builder()
                    .userId(material.getUserId())
                    .materialId(material.getMaterialId())
                    .courseId(courseId)
                    .materialType(materialType)
                    .build());
        }
        return material;
    }

    /**
     * 자료를 DELETED로 내리고 모든 연결을 끊는다. 디스크 파일은 여기서 지우지 않는다 —
     * 호출자가 이 메서드가 커밋된 뒤에 지운다.
     *
     * 남기는 것: 행 자체, original_filename, size_bytes, created_at, file_hash, storage_path.
     *   course_topics.source_material_id가 이 id를 참조하므로 provenance 표시에 쓰이고,
     *   storage_path는 파일 삭제가 실패했을 때 고아 파일을 다시 찾는 유일한 단서다.
     * 지우는 것: extracted_text (남겨두면 AI가 계속 읽을 수 있다 — 삭제가 삭제여야 한다),
     *   모든 material_links.
     * 건드리지 않는 것: course_topics / course_notes / course_material_analyses.
     *   이미 apply된 것은 사용자가 확정한 프로젝트 상태고, 미적용 draft는 링크가 사라지면서
     *   apply 게이트에 의해 자동으로 적용 불가가 된다.
     *
     * @return 삭제 표시 직전의 자료. 호출자가 storagePath를 꺼내 파일을 지운다.
     */
    @Transactional
    public CourseMaterial markDeleted(Long userId, Long materialId) {
        CourseMaterial material = courseMaterialMapper.findByIdAndUserId(materialId, userId);
        if (material == null) {
            throw new NotFoundException(ErrorCode.COURSE_MATERIAL_NOT_FOUND);
        }
        materialLinkMapper.deleteAllByMaterialId(materialId, userId);
        courseMaterialMapper.markDeleted(materialId, userId);
        log.info("자료 삭제 표시: userId={}, materialId={}, storagePath={}",
                userId, materialId, material.getStoragePath());
        return material;
    }
}
