package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserContextResponse {

    private Long contextId;
    private String content;
    private UserContextStatus status;
    private ContextSourceType sourceType;
    /** STATED(내가 말한 것) / SELF_REPORT(내 자기평가) / OBSERVED(실행 기록에서 확인) / INFERRED(AI 추정, 확인 전). */
    private com.jungwoo.project.memo.ai.domain.ContextEvidenceType evidenceType;
    private Long courseId;
    private String courseTitle;
    private Long topicId;
    /** 단원 이름(조회한 곳에서 채운다 — 전역 목록은 비어 있을 수 있다). */
    private String topicTitle;
    private java.time.LocalDate scopeStart;
    private java.time.LocalDate scopeEnd;
    private String selfLevel;
    /** 종류(진도·시험 범위·막힌 곳·해결…). 예전 기억은 null. */
    private com.jungwoo.project.memo.ai.domain.FactKind factKind;
    private String factLabel;
    /** 해결의 도움 수준: SOLO / GUIDED. */
    private String helpLevel;
    private LocalDateTime saidAt;
    private Long sourceMessageId;
    private Long supersedesContextId;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
