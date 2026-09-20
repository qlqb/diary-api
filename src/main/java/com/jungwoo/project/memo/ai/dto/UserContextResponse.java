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
    private java.time.LocalDate scopeStart;
    private java.time.LocalDate scopeEnd;
    private String selfLevel;
    private Long sourceMessageId;
    private Long supersedesContextId;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
