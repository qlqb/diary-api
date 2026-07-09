package com.jungwoo.project.memo.planitem.domain;

import com.jungwoo.project.memo.schedule.domain.ScheduleBlockType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 계획 항목 조정 이벤트.
 *
 * 대상 해석 정책:
 * - todoId만 있음          = 아직 블록으로 배치되지 않은 Todo에 대한 이벤트 (1차-B에서 사용)
 * - scheduleBlockId만 있음 = 특정 날짜 계획 항목에 대한 이벤트
 * - 둘 다 있음             = Todo에서 생성되어 배치된 블록의 이벤트. 집계 시 블록 기준 우선 해석.
 *
 * 무결성 원칙:
 * 블록 이벤트의 todoId는 클라이언트에게 받지 않는다.
 * 서버가 ScheduleBlock.todoId에서 복사해 채운다. (불일치가 생길 수 없는 생성 방식)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanItemEvent {

    private Long planItemEventId;
    private Long userId;
    private Long todoId;
    private Long scheduleBlockId;
    private PlanItemEventType eventType;
    private LocalDate eventDate;      // 조정 행위가 일어난 날
    private LocalDate fromDate;       // MOVED: 원래 날짜
    private LocalDate toDate;         // MOVED: 이동한 날짜
    private String beforeTitle;      // REDUCED: 줄이기 전 제목
    private String afterTitle;       // REDUCED: 줄인 후 제목
    private ScheduleBlockType beforeBlockType;
    private ScheduleBlockType afterBlockType;
    private LocalDateTime beforeStartTime;
    private LocalDateTime afterStartTime;
    private LocalDateTime beforeEndTime;
    private LocalDateTime afterEndTime;
    private String memo;
    private LocalDateTime createdAt;
}
