package com.jungwoo.project.memo.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Configuration
public class JacksonConfig {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return Jackson2ObjectMapperBuilder.json().build();
    }
}
