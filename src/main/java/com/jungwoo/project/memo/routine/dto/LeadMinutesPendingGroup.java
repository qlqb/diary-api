package com.jungwoo.project.memo.routine.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * 아직 이동시간을 정하지 않은(lead_minutes IS NULL) 일정 묶음 하나. 계획 초안을 만들기 전에
 * 질문 카드가 이 단위로 한 줄씩 묻는다.
 *
 * <p>지금 묶음은 "수업"(courseId != null인 반복 일정) 하나다 — 통학은 수업마다 다르지 않아
 * 한 번만 물으면 되고, 수업 N개가 카드 N줄이 되면 성가시다. 약속·다른 루틴에 이동시간이
 * 붙게 되면 묶음이 늘어난다.
 *
 * @param groupKey   묶음 식별자. 화면이 행의 key로 쓴다
 * @param label      묶음 이름("수업")
 * @param routineIds 이 묶음에 속한 반복 일정 전부. 답은 전부에 같은 값으로 저장된다
 * @param sample     "화 14:00"처럼 보여줄 시각 몇 개(최대 3, 기간 안 첫 발생분부터)
 */
public record LeadMinutesPendingGroup(
        String groupKey,
        String label,
        List<Long> routineIds,
        List<Sample> sample
) {
    public record Sample(DayOfWeek dayOfWeek, LocalTime startTime) {
    }
}
