package com.jungwoo.project.memo.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 애플리케이션 전체의 에러 코드 정의
 *
 * 각 에러는 다음 정보를 포함:
 * - HTTP 상태 코드
 * - 에러 코드 (클라이언트가 에러 타입을 식별하는 용도)
 * - 에러 메시지 (사용자에게 표시될 메시지)
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ===== 400 Bad Request =====
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "E400_001", "입력값이 올바르지 않습니다"),
    INVALID_TYPE_VALUE(HttpStatus.BAD_REQUEST, "E400_002", "입력 타입이 올바르지 않습니다"),
    MISSING_INPUT_VALUE(HttpStatus.BAD_REQUEST, "E400_003", "필수 입력값이 누락되었습니다"),
    INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "E400_004", "종료 시각은 시작 시각보다 이후여야 합니다"),
    MOVE_TARGET_DATE_INVALID(HttpStatus.BAD_REQUEST, "E400_006", "이동 대상 날짜가 현재 날짜와 같습니다"),
    REDUCE_TITLE_UNCHANGED(HttpStatus.BAD_REQUEST, "E400_007", "줄인 후 제목이 기존 제목과 같습니다"),
    TIME_FIXED_REQUIRES_TIME(HttpStatus.BAD_REQUEST, "E400_008", "시간 고정 블록은 시작/종료 시각이 필요합니다"),
    PARTIAL_TIME_RANGE(HttpStatus.BAD_REQUEST, "E400_009", "시작/종료 시각은 함께 입력해야 합니다"),
    TASK_MUST_NOT_HAVE_TIME(HttpStatus.BAD_REQUEST, "E400_011", "시간 미지정 작업은 시작/종료 시각을 가질 수 없습니다"),

    // ===== 401 Unauthorized =====
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "E401_001", "인증이 필요합니다"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "E401_002", "이메일 또는 비밀번호가 올바르지 않습니다"),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "E401_003", "토큰이 만료되었습니다"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "E401_004", "유효하지 않은 토큰입니다"),

    // ===== 403 Forbidden =====
    FORBIDDEN(HttpStatus.FORBIDDEN, "E403_001", "접근 권한이 없습니다"),
    NOT_RESOURCE_OWNER(HttpStatus.FORBIDDEN, "E403_002", "리소스 소유자만 접근할 수 있습니다"),

    // ===== 404 Not Found =====
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "E404_001", "요청한 리소스를 찾을 수 없습니다"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "E404_002", "사용자를 찾을 수 없습니다"),
    DIARY_NOT_FOUND(HttpStatus.NOT_FOUND, "E404_003", "일기를 찾을 수 없습니다"),
    DIARY_REVISION_NOT_FOUND(HttpStatus.NOT_FOUND, "E404_004", "일기 수정 이력을 찾을 수 없습니다"),
    TODO_NOT_FOUND(HttpStatus.NOT_FOUND, "E404_005", "할 일을 찾을 수 없습니다"),
    SCHEDULE_BLOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "E404_006", "시간 블록을 찾을 수 없습니다"),

    // ===== 409 Conflict =====
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "E409_001", "이미 존재하는 리소스입니다"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "E409_002", "이미 사용 중인 이메일입니다"),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "E409_003", "현재 상태에서는 수행할 수 없는 작업입니다"),

    // ===== 500 Internal Server Error =====
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "E500_001", "서버 내부 오류가 발생했습니다");

    /** HTTP 상태 코드 */
    private final HttpStatus status;

    /** 에러 코드 (클라이언트 식별용) */
    private final String code;

    /** 에러 메시지 */
    private final String message;
}
