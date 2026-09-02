package com.jungwoo.project.memo.ai.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 일정 후보 적용 요청.
 *
 * <p>editedPayload가 없으면 저장된 원본 후보를 그대로 쓴다. 사용자가 카드에서 고친 값은
 * 여기에 실려 오고, 서버는 그 값을 원본과 똑같은 검증에 통과시킨 뒤에만 만든다.
 *
 * <p>kind는 받지 않는다 — 후보가 이미 자기 종류를 알고 있고, 요청으로 바꿀 수 있게 하면
 * 약속 후보를 반복 일정으로 둔갑시키는 경로가 생긴다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleSuggestionApplyRequest {

    private Map<String, Object> editedPayload;
}
