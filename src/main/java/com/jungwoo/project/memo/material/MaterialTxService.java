package com.jungwoo.project.memo.material;

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
}
