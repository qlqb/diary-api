package com.jungwoo.project.memo.material.extract;

/**
 * 원문을 읽지 못한 이유. 사용자에게 "왜 안 읽혔는지"를 말하기 위해 종류를 나눈다 —
 * 암호 문서는 사용자가 암호를 풀어 다시 올려야 하고, 손상 파일은 원본을 확인해야 하며,
 * 미지원 버전은 다른 형식으로 저장해야 한다. 전부 "추출 실패"로 뭉치면 무엇을 해야 할지 알 수 없다.
 */
public class DocumentExtractionException extends RuntimeException {

    public enum Reason {
        /** 암호가 걸렸거나 배포용으로 잠긴 문서. */
        ENCRYPTED("암호가 걸린 문서예요. 암호를 푼 파일로 다시 올려주세요"),
        /** 형식은 맞지만 내용이 깨졌거나 파서가 읽을 수 없다. */
        CORRUPTED("파일이 손상됐거나 이 형식으로 읽을 수 없어요"),
        /** 형식은 알지만 지원 범위 밖의 판(예: nbformat 3, HWP 3.0). */
        UNSUPPORTED_VERSION("지원하지 않는 문서 판이에요"),
        /** 상한을 넘겨 읽기를 포기했다. */
        TOO_LARGE("파일이 너무 커서 읽지 못했어요"),
        /** 제한 시간 안에 읽지 못했다. */
        TIMEOUT("문서를 읽는 데 너무 오래 걸려 중단했어요");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final Reason reason;

    public DocumentExtractionException(Reason reason) {
        this(reason, reason.message());
    }

    public DocumentExtractionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DocumentExtractionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
