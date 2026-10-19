package com.jungwoo.project.memo.ai.draft;

/**
 * draft 필드 값이 <b>어디서 왔는가</b>. "믿고 진행해도 되는가"(confirmationRequired)와는 별개 축이다.
 *
 * <ul>
 *   <li>{@link #USER} 사용자 발화에 명시. confirmationRequired=false.
 *   <li>{@link #INFERRED} 발화를 해석해 얻음. confirmationRequired는 모델이 제안하되 서버 규칙이 덮어쓴다.
 *   <li>{@link #DB} resolver가 저장된 사실에서 채움(학기 종료일 등). false.
 *   <li>{@link #SYSTEM} 현재 날짜 등 실행 환경 값. false.
 *   <li>{@link #DEFAULT} {@link DraftSlotRegistry}에 명시된 제품 정책 기본값. 모델의 추측이 아니다.
 * </ul>
 *
 * <p>"모델이 근거 없이 채운 값"이라는 출처는 없다. 모델은 USER/INFERRED만 낼 수 있고, 근거가
 * 약하면 INFERRED + confirmationRequired=true다. 모델이 DB/SYSTEM/DEFAULT를 출처로 내면 서버가
 * 그 op를 버린다({@link DraftTurnResolver}).
 */
public enum FieldSource {
    USER,
    INFERRED,
    DB,
    SYSTEM,
    DEFAULT;

    /** 모델이 낼 수 있는 출처인가. */
    public boolean modelMayClaim() {
        return this == USER || this == INFERRED;
    }
}
