package com.jungwoo.project.memo.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Spring Boot 4 기본값은 Jackson 3(tools.jackson)이라 클래식 Jackson 2
 * (com.fasterxml.jackson.databind.ObjectMapper) 빈이 자동 구성되지 않는다
 * (spring.http.converters.preferred-json-mapper=jackson2를 명시하지 않는 한
 * Jackson2HttpMessageConvertersConfiguration이 매치되지 않음). 이 프로젝트 코드
 * 전반(AiConversationService, ExecutionItemService 등)이 이미 클래식
 * ObjectMapper를 직접 주입받는 전제로 작성되어 있으므로, JSON 응답 변환용이 아니라
 * "코드에서 쓸 ObjectMapper 빈 자체"를 명시적으로 하나 등록한다.
 *
 * <p>날짜를 ISO 문자열로 쓴다. {@code Jackson2ObjectMapperBuilder.json()}은 Boot의
 * 커스터마이저를 거치지 않아 {@code WRITE_DATES_AS_TIMESTAMPS}가 켜진 채로 나온다 —
 * Boot가 자동 구성하는 매퍼(기본값 false)와 반대다. 그대로 두면 이 빈으로 직렬화한
 * LocalDateTime이 {@code [2026,9,7,17,0]}이 되고, 같은 값을 모델이 낸 JSON은
 * {@code "2026-09-07T17:00:00"}이라 한 컬럼에 두 모양이 섞인다. 실제로 일정 후보
 * payload에서 그렇게 됐다.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
