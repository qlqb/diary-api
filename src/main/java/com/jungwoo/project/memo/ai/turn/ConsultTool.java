package com.jungwoo.project.memo.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 상담 도구 단계에서 모델이 고를 수 있는 도구 하나. 도구는 <b>읽기와 턴 작업 공간의 임시 제안</b>만 한다 — 원본 데이터도, 합의·기억도
 * 바꾸지 않는다. 작업 공간은 턴이 성공으로 끝날 때 한 트랜잭션으로 저장된다. 적용·승인 도구는 없다.
 *
 * <p>사용자 id는 인자에 없다. {@link TurnWorkspace}가 서버가 확인한 사용자로 실행한다.
 */
public interface ConsultTool {

    String name();

    String description();

    /** JSON Schema(object). */
    String inputSchema();

    /** 진행 표시 문구(예: "일정 확인 중"). */
    String progressLabel();

    /**
     * @return 모델에게 줄 결과(JSON 문자열). 결과 코드(result)를 담는다 — 없음·조회 실패·이미 처리됨·충돌·입력 오류를 구분한다
     * @throws IllegalArgumentException 인자 오류(실행기가 INVALID_ARGS로 돌려준다)
     */
    String call(TurnWorkspace workspace, JsonNode args);
}
