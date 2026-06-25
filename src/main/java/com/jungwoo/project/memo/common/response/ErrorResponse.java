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
     * ErrorCode로부터 ErrorResponse 생성
     *
     * @param errorCode ErrorCode
     * @return ErrorResponse
     */
    public static ErrorResponse of(ErrorCode errorCode) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .errors(new ArrayList<>())
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