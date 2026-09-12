package com.jungwoo.project.memo.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 실제로 확정된 장기 컨텍스트 한 건. user_contexts 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * AI는 이 테이블을 직접 바꾸지 않는다 — 사용자가 ai_context_change_suggestions 후보를
 * 승인(apply)했을 때만 이 행이 생기거나 상태가 바뀐다. category 같은 생활 사건 유형은
 * 이 엔티티에 없다 — content(사람이 읽는 문장)가 핵심 데이터다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserContext {

    private Long contextId;

    private Long userId;

    private String content;

    private UserContextStatus status;

    private ContextSourceType sourceType;

    private Long sourceMessageId;

    /** SUPERSEDE로 이 행이 생겼다면 대체된 기존 context_id. */
    private Long supersedesContextId;

    private LocalDateTime confirmedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
