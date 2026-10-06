package com.jungwoo.project.memo.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import lombok.Builder;
import lombok.Getter;
import org.springframework.validation.BindingResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * API 에러 응답 형식
 *
 * JSON 응답 예시:
 * {
 *   "timestamp": "2026-05-11T12:30:45",
 *   "code": "E404_003",
 *   "message": "일기를 찾을 수 없습니다",
 *   // details: 오류가 구조화된 부가 정보를 낼 때만 포함된다
 *   "errors": [
 *     {
 *       "field": "title",
 *       "value": "",
 *       "reason": "제목은 필수입니다"
 *     }
 *   ]
 * }
 */
@Getter
@Builder
public class ErrorResponse {

    /** 에러 발생 시간 */
    private final LocalDateTime timestamp;

    /** 에러 코드 (E400_001, E404_003 등) */
    private final String code;

    /** 에러 메시지 */
    private final String message;

    /** 필드별 검증 에러 (Validation 에러 시에만 포함) */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)  // 비어있으면 JSON에서 제외
    private final List<FieldError> errors;

    /**
     * 오류별 구조화된 부가 정보. 대부분의 응답에는 없다(NON_NULL이라 JSON에서 빠진다).
     *
     * <p>errors[]와 역할이 다르다. errors[]는 BindingResult 전용이라 field/value/reason
     * 문자열 3개뿐이고, "어떤 예외 id들이 걸렸는가" 같은 구조를 담을 자리가 아니다. 그렇다고
     * message에 나열하면 서버 문구와 화면이 결합되어 문구를 다듬는 순간 화면이 깨진다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Object details;

    /**
     * ErrorCode로부터 ErrorResponse 생성
     *
     * @param errorCode ErrorCode
     * @return ErrorResponse
     */
    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, (Object) null);
    }

    /**
     * ErrorCode와 구조화된 부가 정보로부터 ErrorResponse 생성
     *
     * @param errorCode ErrorCode
     * @param details 화면이 읽을 부가 정보. null이면 응답에서 빠진다
     * @return ErrorResponse
     */
    public static ErrorResponse of(ErrorCode errorCode, Object details) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .errors(new ArrayList<>())
                .details(details)
                .build();
    }

    /**
     * ErrorCode와 BindingResult로부터 ErrorResponse 생성
     * Validation 에러 시 필드별 에러 정보 포함
     *
     * @param errorCode ErrorCode
     * @param bindingResult BindingResult (Validation 결과)
     * @return ErrorResponse
     */
    public static ErrorResponse of(ErrorCode errorCode, BindingResult bindingResult) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .errors(FieldError.of(bindingResult))
                .build();
    }

    /**
     * 필드 검증 에러 정보
     *
     * 어떤 필드에서 어떤 값이 어떤 이유로 검증 실패했는지 포함
     */
    @Getter
    @Builder
    public static class FieldError {

        /** 에러가 발생한 필드명 */
        private final String field;

        /** 입력된 값 */
        private final String value;

        /** 에러 사유 */
        private final String reason;

        /**
         * BindingResult로부터 FieldError 리스트 생성
         *
         * @param bindingResult Spring Validation 결과
         * @return FieldError 리스트
         */
        public static List<FieldError> of(BindingResult bindingResult) {
            // FieldError를 우리의 FieldError로 변환
            return bindingResult.getFieldErrors().stream()
                    .map(error -> FieldError.builder()
                            .field(error.getField())
                            .value(error.getRejectedValue() == null ?
                                    "" : error.getRejectedValue().toString())
                            .reason(error.getDefaultMessage())
                            .build())
                    .collect(Collectors.toList());
        }
    }
}