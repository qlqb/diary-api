package com.jungwoo.project.memo.ai;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계획 proposal item을 만드는 두 경로(전용 계획 생성 PlanDraftService, 대화 경로
 * OpenAiConsultationClient.SYSTEM_PROMPT)가 같은 항목 품질 규칙을 실어야 한다는 계약.
 *
 * 여기서 보는 것은 규칙 문구가 최종 프롬프트에 실렸는지까지다. 모델이 그 규칙을 지키는지는
 * 단위 테스트로 단정하지 않는다 — 서버 재기동 후 기존 요청으로 수동 비교한다.
 *
 * 조각은 줄바꿈을 타지 않게 골랐다. 두 프롬프트의 들여쓰기가 달라도 같은 자리에서 줄이
 * 접히도록 원문을 맞춰 두었으므로, 한쪽만 고치면 이 테스트가 깨진다 — 그게 의도다.
 */
public final class PlanItemPromptRules {

    private PlanItemPromptRules() {
    }

    public static final List<String> SHARED_PHRASES = List.of(
            // 구체적인 행동
            "앉아서 바로 시작할 수 있는 학습 행동이어야",
            "과목·대상·행동이 드러나야 하고",
            // 확인 가능한 완료 기준(형식까지)
            "확인 가능한 완료 기준을 \"행동 · 완료: 기준\" 형식으로 적는다",
            // 다시 판단하게 만드는 표현 금지
            "\"복습 및 실습\"처럼 무엇을 할지 사용자가 다시 판단해야",
            "나쁜 예: 제목 \"자료구조 핵심 복습\", description \"교재 진도 정리\"",
            "좋은 예: 제목 \"자료구조 · 반복문 코드의 Big-O 판단\"",
            // 근거 없는 출처 표현 금지
            "자료 파일명·교재 장·쪽수·\"같은 출처\"처럼 거기 없는 출처 표현을 만들지 않는다",
            "근거가 없으면 출처를 적지 않는다",
            // 요청하지 않은 관리 작업 금지
            "\"전체 학습 구조 설계\", \"기본 학습목록 만들기\", \"커리큘럼 정리\"",
            "관리 작업은 사용자가",
            "요청했을 때만 만든다",
            "계획 요청은 학습 실행 항목을 달라는 뜻이다"
    );

    public static void assertCarriesRules(String prompt) {
        for (String phrase : SHARED_PHRASES) {
            assertThat(prompt).as("프롬프트에 규칙 조각이 있어야 한다: %s", phrase).contains(phrase);
        }
    }
}
