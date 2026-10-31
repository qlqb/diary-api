package com.jungwoo.project.memo.ai.domain;

/**
 * 제안 항목 하나가 실제 데이터에 무엇을 하려는지.
 *
 * 지금까지 제안은 항상 새 실행 조각을 만드는 것뿐이었다(CREATE). "오늘 너무 피곤해, 줄여줘"
 * 같은 요청을 새 조각 생성으로만 답하면 기존 계획은 그대로 남고 할 일만 늘어난다 — 그래서
 * 기존 조각을 대상으로 하는 세 가지 조정을 추가한다.
 *
 * 새 컬럼을 만들지 않는다. 이 값은 ai_proposal_items.original_payload(스키마 없는 JSON)에
 * 함께 담기고, 대상 조각은 이미 존재하던 target_item_id/base_version 컬럼이 가리킨다.
 * base_version 덕분에 제안을 만든 뒤 사용자가 그 조각을 직접 고쳤다면 적용이 409로 막힌다.
 */
public enum ProposalOperation {

    /** 새 실행 조각을 만든다. 값이 없는(예전에 저장된) payload도 이것으로 취급한다. */
    CREATE,

    /** 기존 조각의 분량/제목을 줄인다. */
    REDUCE,

    /** 기존 조각을 다른 날짜로 옮긴다. */
    MOVE,

    /** 기존 조각을 오늘 목록에서 뺀다(보류). 삭제하지 않는다 — 되돌릴 수 있어야 한다. */
    DROP
}
