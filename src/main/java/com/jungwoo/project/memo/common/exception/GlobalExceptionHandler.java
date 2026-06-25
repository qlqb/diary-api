package com.jungwoo.project.memo.common.exception;

import com.jungwoo.project.memo.common.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 처리 핸들러
 *
 * @RestControllerAdvice: 모든 @RestController에서 발생하는 예외를 처리
 *
 * 처리 흐름:
 * 1. 컨트롤러나 서비스에서 예외 발생
 * 2. 해당 예외 타입에 맞는 @ExceptionHandler 메서드 실행
 * 3. ErrorResponse 객체 생성
 * 4. 적절한 HTTP 상태 코드와 함께 JSON 응답 반환
 *
 * 장점:
 * - 예외 처리 로직이 한 곳에 집중됨
 * - 컨트롤러에서 try-catch 불필요
 * - 일관된 에러 응답 형식
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * BusinessException 처리
     * 우리가 정의한 모든 비즈니스 예외의 최상위 클래스
     *
     * @param ex BusinessException
     * @return ErrorResponse
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        log.warn("BusinessException: code={}, message={}",
                ex.getErrorCode().getCode(), ex.getMessage());

        ErrorCode errorCode = ex.getErrorCode();
        ErrorResponse response = ErrorResponse.of(errorCode);

        return new ResponseEntity<>(response, errorCode.getStatus());
    }

    /**
     * Validation 예외 처리
     * @Valid, @Validated에서 발생하는 검증 실패
     *
     * 예: @NotBlank, @Email, @Size 등의 제약조건 위반
     *
     * @param ex MethodArgumentNotValidException
     * @return ErrorResponse
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex) {

        log.warn("Validation 실패: {}", ex.getBindingResult());

        ErrorResponse response = ErrorResponse.of(
                ErrorCode.INVALID_INPUT_VALUE,
                ex.getBindingResult()  // 필드별 에러 메시지 포함
        );

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * BindException 처리
     * 폼 데이터 바인딩 실패
     *
     * @param ex BindException
     * @return ErrorResponse
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBindException(BindException ex) {
        log.warn("Binding 실패: {}", ex.getBindingResult());

        ErrorResponse response = ErrorResponse.of(
                ErrorCode.INVALID_INPUT_VALUE,
                ex.getBindingResult()
        );

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * Spring Security AuthenticationException 처리
     * 인증 실패 (잘못된 토큰, 만료된 토큰 등)
     *
     * @param ex AuthenticationException
     * @return ErrorResponse
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException ex) {

        log.warn("인증 실패: {}", ex.getMessage());

        ErrorResponse response = ErrorResponse.of(ErrorCode.UNAUTHORIZED);

        return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
    }

    /**
     * Spring Security AccessDeniedException 처리
     * 권한 부족 (인증은 되었지만 권한이 없음)
     *
     * @param ex AccessDeniedException
     * @return ErrorResponse
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex) {

        log.warn("권한 없음: {}", ex.getMessage());

        ErrorResponse response = ErrorResponse.of(ErrorCode.FORBIDDEN);

        return new ResponseEntity<>(response, HttpStatus.FORBIDDEN);
    }

    /**
     * 예상하지 못한 모든 예외 처리
     * 마지막 방어선 역할
     *
     * @param ex Exception
     * @return ErrorResponse
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        log.error("예상치 못한 오류 발생", ex);

        ErrorResponse response = ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR);

        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}