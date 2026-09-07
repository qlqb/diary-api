package com.jungwoo.project.memo.ai.draft.dto;

import java.util.List;

/**
 * 이번 발화가 어느 draft를 대상으로 하는지(targets), 새로 만들 draft는 무엇인지(create).
 * 모델의 제안이다 — 실제 대상 집합(effectiveTargets)은 서버가 draftOps까지 합쳐 정한다.
 */
public record DraftRouting(
        List<Long> targets,
        List<DraftCreateSpec> create
) {
}
