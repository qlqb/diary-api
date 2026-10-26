package com.jungwoo.project.memo.ai.change;

/**
 * op 하나의 확인 결과. 모델(도구 결과)과 화면(카드)이 같은 코드를 본다 — 조회 실패와 데이터 없음, 이미 처리됨, 그사이 바뀜을 구분한다.
 *
 * @param op 확인하며 검토 당시 상태(version·해시·스냅샷)를 채운 op. 실패면 들어온 op 그대로
 */
public record ChangeCheck(String code, String message, ChangeOp op) {

    public static final String OK = "OK";
    /** 대상이 없거나 이 사용자의 것이 아니다(남의 id인지 알리지 않는다). */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 값이 규칙에 맞지 않는다(그날은 그 반복 일정이 없는 날, 시간 역전 등). */
    public static final String INVALID = "INVALID";
    /** 이미 그 상태다(이미 그날 빠짐, 이미 완료). */
    public static final String ALREADY_DONE = "ALREADY_DONE";
    /** 같은 대상에 다른 변경이 이미 있다(같은 날 다른 예외 등). */
    public static final String CONFLICT = "CONFLICT";
    /** 검토한 뒤 대상이 바뀌었다 — 다시 검토해야 한다. */
    public static final String STALE = "STALE";
    /** 조회 중 서버 오류 — 대상이 없다는 뜻이 아니다. */
    public static final String READ_FAILED = "READ_FAILED";

    public static ChangeCheck ok(ChangeOp op) {
        return new ChangeCheck(OK, null, op);
    }

    public static ChangeCheck fail(String code, String message, ChangeOp op) {
        return new ChangeCheck(code, message, op);
    }

    public boolean isOk() {
        return OK.equals(code);
    }
}
