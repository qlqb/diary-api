package com.jungwoo.project.memo.ai.draft.resolver;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** 사용자 시간대 기준 오늘 → {@code SYSTEM / CURRENT_DATE}. Clock을 받아 테스트에서 고정한다. */
public final class CurrentDateResolver {

    private CurrentDateResolver() {
    }

    public static LocalDate today(Clock clock, ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }
}
