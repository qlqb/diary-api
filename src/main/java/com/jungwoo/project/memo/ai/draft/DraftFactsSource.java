package com.jungwoo.project.memo.ai.draft;

import java.time.LocalDate;

/**
 * 요청 기간에 맞는 실제 데이터를 돌려준다. {@link DraftTurnResolver}가 DB를 모른 채 기간별
 * 사실을 얻는 통로다.
 *
 * <p>필요한 이유는 순서 때문이다. LLM을 부르기 전에는 사용자가 어느 기간을 말할지 모르므로
 * 기본 기간(오늘~+14일)으로 조회해 프롬프트에 싣는다. 그런데 "이번 주"나 그보다 뒤의 기간은
 * 모델 응답의 dateRange를 읽어야 알 수 있고, 그 기간이 기본 기간 밖이면 판정 전에 다시
 * 조회해야 한다. 조회하지 않은 기간을 "근무 없음"으로 답하면 거짓말이 된다.
 *
 * <p>구현은 같은 기간을 두 번 조회하지 않는다({@code DraftFactsService.TurnFacts}).
 */
@FunctionalInterface
public interface DraftFactsSource {

    /**
     * @param from 첫 날(포함)
     * @param to   마지막 날(포함)
     */
    DraftFacts forRange(LocalDate from, LocalDate to);

    /** 기간과 무관하게 늘 같은 사실을 주는 조회기. 테스트와 기간이 없는 판정이 쓴다. */
    static DraftFactsSource fixed(DraftFacts facts) {
        return (from, to) -> facts;
    }
}
