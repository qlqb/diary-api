package com.jungwoo.project.memo.common.time;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 이 앱에서 시각을 고르는 단위는 분이다. 초가 섞이면 길이 계산이 잘리고, 화면에 보이는 값과
 * 저장된 값이 어긋난다. 30초짜리 일정은 만들 수 없어야 한다.
 *
 * <p>원래 이 판정은 {@code PlacementDuration}에만 있었다. 두 가지 이유로 여기로 옮긴다.
 * 첫째, 반복 일정(routines)의 시각은 {@link LocalTime}이라 {@link LocalDateTime} 버전을
 * 그대로 쓸 수 없다. 둘째, {@code PlacementDuration}은 "실행 조각의 길이 규칙"이라 반복
 * 일정이 거기에 의존하면 결합이 이상해진다.
 *
 * <p>판정을 양쪽에 복사해 두지 않는 것이 요점이다 — 같은 규칙이 두 곳에 있으면 한쪽만
 * 고쳐져 어긋난다. expected_minutes가 시각과 어긋났던 사고가 정확히 그 형태였다.
 * {@code PlacementDuration.isMinutePrecision}은 이 클래스에 위임한다.
 */
public final class MinutePrecision {

    private MinutePrecision() {
    }

    public static boolean isMinutePrecision(LocalTime time) {
        return time.getSecond() == 0 && time.getNano() == 0;
    }

    public static boolean isMinutePrecision(LocalDateTime time) {
        return time.getSecond() == 0 && time.getNano() == 0;
    }
}
