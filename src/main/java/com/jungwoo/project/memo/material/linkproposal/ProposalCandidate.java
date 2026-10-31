package com.jungwoo.project.memo.material.linkproposal;

import java.util.Objects;

/**
 * 제안 한 번을 만드는 동안만 사는 요청 범위 값 객체 — 후보 자료와 그 자료에서 뽑은 발췌.
 *
 * CourseMaterial 엔티티를 담지 않고 필요한 값만 복사한다. 요청 중 엔티티의 가변 상태나
 * 영속성 컨텍스트에 의존하지 않는 진짜 불변 값이 되고, 보정 로직 단위 테스트에서 픽스처를
 * 한 줄로 만들 수 있다.
 *
 * 서비스 인스턴스 필드에 보관하지 않는다 — Spring 서비스는 싱글턴이라 동시 요청의 발췌가
 * 섞인다. 프롬프트 조립·evidence 대조·응답 조립에 인자로 전달한다.
 *
 * API 계약이 아니므로 dto 패키지에 두지 않는다. 접근 제한자를 붙이지 않는 것도 의도다 —
 * 같은 패키지의 ProposalNormalizer와 테스트만 쓴다.
 */
record ProposalCandidate(
        Long materialId,
        String originalFilename,
        String excerpt
) {
    ProposalCandidate {
        Objects.requireNonNull(materialId);
        excerpt = excerpt == null ? "" : excerpt;
    }

    boolean hasExcerpt() {
        return !excerpt.isBlank();
    }
}
