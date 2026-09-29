package com.jungwoo.project.memo.plan.help;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 시작 도움. 항목의 첫 행동을 구체화한 것이지 계획을 바꾼 것이 아니다.
 *
 * @param firstAction          지금 바로 할 첫 행동 한두 문장
 * @param starter              필요할 때만 주는 짧은 시작 활동(5~15분). 없으면 null
 * @param where                자료의 어디를 열지. 인용 구간이 없으면 null
 * @param scopeChangeRequested 요청이 범위·시간을 바꾸자는 것이었다 — 도움은 범위를 바꾸지 않으므로 계획 조정을 권한다
 * @param grounded             인용한 자료 구간을 근거로 만들었는가(false면 항목 설명만으로 만든 안내)
 * @param stale                항목 글이나 인용 원문이 바뀐 뒤의 예전 안내
 */
public record StartHelpResponse(
        Long helpId,
        String requestKind,
        String requestText,
        String firstAction,
        Starter starter,
        String where,
        boolean scopeChangeRequested,
        boolean grounded,
        boolean stale,
        LocalDateTime createdAt
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Starter(String title, Integer minutes, List<String> steps) {
    }

    /** 저장하는 모양. 화면 응답과 같은 필드를 쓴다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stored(String firstAction, Starter starter, String where, Boolean scopeChangeRequested, Boolean grounded) {
    }
}
