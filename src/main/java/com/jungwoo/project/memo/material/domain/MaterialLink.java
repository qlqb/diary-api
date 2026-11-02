package com.jungwoo.project.memo.material.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 자료(Material) ↔ 프로젝트(Course) 연결. material_links 테이블과 1:1 대응.
 *
 * materialType은 자료 자체의 성질이 아니라 "이 프로젝트가 이 자료를 무엇으로 쓰는가"다 —
 * 같은 파일이 A 프로젝트에서는 SYLLABUS, B에서는 OTHER일 수 있어서 여기 둔다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialLink {

    private Long linkId;

    private Long userId;

    private Long materialId;

    private Long courseId;

    private MaterialType materialType;

    private LocalDateTime linkedAt;
}
