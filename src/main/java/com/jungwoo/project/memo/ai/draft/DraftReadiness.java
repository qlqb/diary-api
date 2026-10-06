package com.jungwoo.project.memo.ai.draft;

/**
 * 서버가 draft마다 계산하는 상태.
 *
 * <pre>
 * missing_required 비어 있음 AND confirmationRequired=true인 필드 없음 → READY
 * missing_required 있음                                              → NEEDS_INPUT
 * 그 외 (unconfirmed만 남음)                                          → NEEDS_CONFIRM
 * </pre>
 */
public enum DraftReadiness {
    READY,
    NEEDS_INPUT,
    NEEDS_CONFIRM
}
