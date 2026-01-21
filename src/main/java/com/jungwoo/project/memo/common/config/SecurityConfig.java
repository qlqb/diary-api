package com.jungwoo.project.memo.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                .csrf((csrf) -> csrf.disable()
                        .authorizeHttpRequests((authorizeHttpRequests) -> authorizeHttpRequests.anyRequest()
                                .permitAll()));
        return http.build();
    }
}
