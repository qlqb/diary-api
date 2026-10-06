package com.jungwoo.project.memo.ai.draft.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jungwoo.project.memo.ai.draft.DraftType;
import com.jungwoo.project.memo.ai.draft.FieldSource;

/**
 * 모델이 제안하는 draft 수정 하나(구조화 출력 draftOps의 원소). 서버가 적용·검증한다.
 *
 * <pre>
 * {"draftId": 31,      "op": "SET",    "field": "endDate", "value": "2026-12-11", "source": "USER", "confirmationRequired": false}
 * {"draftId": 31,      "op": "CLEAR",  "field": "startTime"}
 * {"draftId": 31,      "op": "RETYPE", "draftType": "CREATE_ROUTINE"}
 * {"draftId": 32,      "op": "CANCEL"}
 * {"draftId": "new-0", "op": "SET",    "field": "durationMinutes", "value": 60, "source": "USER"}
 * </pre>
 *
 * @param draftId 기존 draft의 숫자 id 또는 같은 턴 create의 임시 id("new-0"). 문자열로 받는다
 */
public record DraftOp(
        String draftId,
        String op,
        String field,
        JsonNode value,
        FieldSource source,
        Boolean confirmationRequired,
        DraftType draftType
) {
    public static final String SET = "SET";
    public static final String CLEAR = "CLEAR";
    public static final String RETYPE = "RETYPE";
    public static final String CANCEL = "CANCEL";
}
