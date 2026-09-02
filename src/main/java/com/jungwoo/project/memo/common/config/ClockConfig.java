package com.jungwoo.project.memo.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 현재 시각을 Instant.now()/LocalDate.now() 같은 정적 호출로 코드 곳곳에 흩어 쓰지 않고
 * 주입받게 한다. UTC 기준 Clock을 하나만 두고, 사용자 시간대는 필요한 곳에서
 * ZonedDateTime.withZoneSameInstant(...)로 변환한다 — JVM/서버 OS 기본 시간대에 의존하지
 * 않기 위함이다. 테스트에서는 Clock.fixed(...)로 교체해 시각을 고정할 수 있다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
