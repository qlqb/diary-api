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

    /** 정규화한 본문 키(중복·철회 판정). 예전 행은 null. */
    private String contentKey;

    private UserContextStatus status;

    private ContextSourceType sourceType;

    /** 근거 유형. 옛 행은 STATED(전부 사용자가 확정·승인한 것이다). */
    @Builder.Default
    private ContextEvidenceType evidenceType = ContextEvidenceType.STATED;

    /** 종류. 예전 행은 null(종류 모름). */
    private FactKind factKind;

    /** EXAM_SCOPE의 시험 이름. 같은 과목·종류·이름끼리만 대체한다. */
    private String factLabel;

    /** RESOLVED의 도움 수준: SOLO / GUIDED. 사용자가 말한 것만. */
    private String helpLevel;

    /** 근거가 된 사용자 발화 시각(수동 수정은 수정 시각). 더 늦게 말한 것이 이긴다. 예전 행은 null. */
    private LocalDateTime saidAt;

    /** 적용 범위. 전부 null이면 범위를 모르는(전반적인) 것이다. */
    private Long courseId;
    private Long topicId;
    private Long sectionId;
    private java.time.LocalDate scopeStart;
    private java.time.LocalDate scopeEnd;

    /** 점검 활동의 자기평가 값: KNOW / UNSURE / NEW. 숙달도가 아니다. */
    private String selfLevel;

    private LocalDateTime withdrawnAt;

    private Long sourceMessageId;

    /** SUPERSEDE로 이 행이 생겼다면 대체된 기존 context_id. */
    private Long supersedesContextId;

    private LocalDateTime confirmedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
