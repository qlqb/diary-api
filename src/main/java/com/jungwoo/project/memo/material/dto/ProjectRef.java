package com.jungwoo.project.memo.material.dto;

/**
 * 프로젝트를 가리키는 최소 정보. 제안 생성 입력(ACTIVE 프로젝트 목록)과 응답(동명 경고의
 * 실제 후보, apply로 새로 만든 프로젝트) 양쪽에서 같은 모양으로 쓴다.
 */
public record ProjectRef(Long courseId, String title) {
}
