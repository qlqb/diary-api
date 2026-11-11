package com.jungwoo.project.memo.learning.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** learning_events 행. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningEvent {

    private Long eventId;
    private Long userId;
    private Long courseId;
    private String originKind;
    private Long originId;
    private Integer originRevision;
    private Integer outputNo;
    private String actor;
    private String verb;
    private String objectKind;
    private String objectRef;
    private Integer objectFrom;
    private Integer objectTo;
    private Long topicId;
    private String payload;
    private String evidence;
    private BigDecimal confidence;
    private LocalDateTime claimAt;
    private Long claimSeq;
    private LocalDateTime occurredAt;
    private LocalDateTime recordedAt;
}
