package com.jungwoo.project.memo.ai.scheduleimport;

import java.time.LocalTime;

/**
 * 근무표 셀 하나를 서버가 읽은 결과.
 *
 * <p>★ 이 변환을 모델에게 시키지 않는다. 모델이 {@code "17~23"}을 보고 {@code startTime:
 * "17:00"}을 내면, 그것이 표에 적힌 것을 옮긴 것인지 그럴듯하게 지어낸 것인지 서버가
 * 구분할 방법이 없다. 모델은 셀을 원문 그대로 받아쓰고, 시간으로 바꾸는 것은 여기서 한다.
 *
 * @param raw             표에 적혀 있던 그대로. 사용자에게 "이 칸을 이렇게 읽었어요"를
 *                        보여줄 때 쓴다
 * @param code            범례로 풀어 쓴 경우 그 코드(OP, CL). 직접 시간이 적혀 있었으면 null.
 *                        일정 제목에 붙어 "근무 (OP)"가 된다
 * @param crossesMidnight 종료가 다음 날인가. {@code 18~00}, {@code 22~02}가 여기 해당한다
 */
public record ParsedCell(
        Kind kind,
        String raw,
        LocalTime start,
        LocalTime end,
        boolean crossesMidnight,
        String code
) {

    public enum Kind {
        /** 이 날 일할 시간이 정해져 있다. */
        WORK,

        /**
         * 쉬는 날. 후보를 만들지 않는다 — 빈 시간이 곧 휴무이고, "휴무"라는 일정을 달력에
         * 넣으면 그 시간이 무언가로 차 있는 것처럼 보인다.
         */
        OFF,

        /**
         * 무슨 뜻인지 모르는 칸. 범례에 없는 코드다.
         *
         * <p>추측하지 않는다. 모르는 코드를 그럴듯한 시간으로 채우면 사용자는 자기가 확인한
         * 적 없는 일정을 달력에서 보게 된다. 사용자에게 시간을 묻는다.
         */
        UNRESOLVED
    }

    public static ParsedCell off(String raw) {
        return new ParsedCell(Kind.OFF, raw, null, null, false, null);
    }

    public static ParsedCell unresolved(String raw) {
        return new ParsedCell(Kind.UNRESOLVED, raw, null, null, false, null);
    }

    public static ParsedCell work(String raw, LocalTime start, LocalTime end,
                                  boolean crossesMidnight, String code) {
        return new ParsedCell(Kind.WORK, raw, start, end, crossesMidnight, code);
    }

    public boolean isWork() {
        return kind == Kind.WORK;
    }
}
