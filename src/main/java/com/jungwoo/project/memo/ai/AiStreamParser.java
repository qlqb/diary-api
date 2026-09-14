package com.jungwoo.project.memo.ai;

/**
 * 모델이 스트리밍으로 내보내는 원문 토큰을 "사용자에게 보여줄 reply"와
 * "턴이 끝난 뒤 파싱할 구조화 JSON"으로 나눈다.
 *
 * Spring AI 2.0에서 .stream()과 .entity() 구조화 출력은 같은 호출에서 함께 쓸 수 없다
 * (entity()는 완결된 응답 전체가 있어야 파싱할 수 있다). 그렇다고 사용자 메시지 하나당
 * "답변용 호출"과 "분류용 호출"을 따로 두 번 부르면 요구사항이 금지하는 이중 호출이 된다.
 * 대신 시스템 프롬프트가 모델에게 "reply를 먼저 순수 텍스트로 쓰고, 그 다음 줄에 구분자를
 * 쓰고, 그 아래에만 JSON을 쓰라"고 지시하게 하고, 이 클래스가 그 경계를 스트리밍 중에
 * 안전하게 찾는다 — 결과적으로 모델 호출은 한 번이면서 reply는 토큰 단위로 즉시 보여줄 수
 * 있다. 구분자가 청크 경계에서 잘릴 수 있으므로, 구분자 길이-1만큼은 항상 다음 청크와
 * 합쳐서 재검사한 뒤에만 방출한다.
 *
 * 이 클래스는 턴 하나마다 새로 만드는 상태 저장 객체다 (스레드 세이프하지 않음).
 */
public class AiStreamParser {

    public static final String DELIMITER = "<<<AI_STRUCTURED>>>";

    private final StringBuilder full = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private boolean delimiterFound = false;

    /**
     * 새 청크를 먹이고, 지금 안전하게 사용자에게 보여줘도 되는 reply 조각이 있으면 반환한다.
     * 구분자를 이미 지났으면(구조화 JSON 구간) 항상 빈 문자열을 반환한다.
     */
    public String onChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        full.append(chunk);
        if (delimiterFound) {
            return "";
        }

        pending.append(chunk);
        String buffered = pending.toString();
        int idx = buffered.indexOf(DELIMITER);
        if (idx >= 0) {
            String safe = buffered.substring(0, idx);
            delimiterFound = true;
            pending.setLength(0);
            return safe;
        }

        int safeLen = Math.max(0, buffered.length() - (DELIMITER.length() - 1));
        if (safeLen == 0) {
            return "";
        }
        String safe = buffered.substring(0, safeLen);
        pending.delete(0, safeLen);
        return safe;
    }

    /**
     * 스트림이 끝난 뒤 호출한다. 누적된 전체 텍스트를 기준으로 reply/구조화 JSON을
     * 최종적으로(권위 있게) 나눈다 — onChunk의 점진적 방출은 화면 표시용일 뿐,
     * 실제 저장·파싱은 항상 이 결과를 기준으로 한다.
     */
    public Result finish() {
        String text = full.toString();
        int idx = text.indexOf(DELIMITER);
        if (idx < 0) {
            // 구분자가 끝내 나오지 않음: onChunk가 안전 여유분(DELIMITER.length()-1자)만큼
            // 방출을 보류해뒀을 수 있으므로, 그 꼬리를 여기서 마저 돌려준다.
            return new Result(text.trim(), null, pending.toString());
        }
        String reply = text.substring(0, idx).trim();
        String json = text.substring(idx + DELIMITER.length()).trim();
        return new Result(reply, json.isEmpty() ? null : json, "");
    }

    /**
     * @param reply             사용자에게 보여줄 최종 답변 텍스트
     * @param structuredJson    구분자 뒤 JSON 원문 (구분자가 아예 없었으면 null)
     * @param unemittedTail     구분자가 끝내 안 나왔을 때, onChunk로 아직 방출되지 않고 남아있던
     *                          꼬리 텍스트 (스트리밍 중 보류됐던 부분 — 완료 시점에 마저 보여줘야 한다)
     */
    public record Result(String reply, String structuredJson, String unemittedTail) {
    }
}
