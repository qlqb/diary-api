package com.jungwoo.project.memo.routine;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionResponse;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionSaveRequest;
import com.jungwoo.project.memo.routine.dto.RoutineResponse;
import com.jungwoo.project.memo.routine.dto.RoutineSaveRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 반복 일정 컨트롤러.
 *
 * <pre>
 * GET    /api/routines                          내 반복 일정 목록
 * POST   /api/routines                          만들기
 * PUT    /api/routines/{id}                     고치기 (전체 교체)
 * DELETE /api/routines/{id}                     소프트 삭제
 * GET    /api/routines/occurrences?from=&to=    전개 결과
 * POST   /api/routines/{id}/exceptions          예외 추가
 * PUT    /api/routines/{id}/exceptions/{exId}   예외 수정 (전체 교체)
 * DELETE /api/routines/{id}/exceptions/{exId}   예외 삭제
 * </pre>
 *
 * <p>PATCH가 아니라 PUT이다(RoutineSaveRequest 참고). 낙관적 락(version)은 넣지 않았다 —
 * execution_items에 그것이 있는 이유는 여러 경로가 동시에 쓰기 때문인데, 반복 일정은 학기
 * 초에 몇 번 만들고 안 건드리는 값이고 지금 쓰기 경로가 화면 하나뿐이다. 대화에서 반복
 * 일정을 만드는 경로가 생기면 그때 컬럼 하나를 추가하면 된다.
 */
@Slf4j
@RestController
@RequestMapping("/api/routines")
@RequiredArgsConstructor
public class RoutineController {

    private final RoutineService routineService;
    private final RoutineOccurrenceService routineOccurrenceService;

    @GetMapping
    public ResponseEntity<List<RoutineResponse>> list(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(routineService.list(principal.getUserId()));
    }

    /**
     * 전개 결과. 행이 없으므로 이 응답이 유일한 출처다.
     *
     * <p>RoutineOccurrence를 별도 응답 타입으로 감싸지 않는다. 이 record는 애초에 "응답에만
     * 담기는 값"으로 만든 것이라, 같은 필드를 가진 DTO를 하나 더 두면 둘이 어긋날 자리만
     * 생긴다.
     */
    @GetMapping("/occurrences")
    public ResponseEntity<List<RoutineOccurrence>> occurrences(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(routineOccurrenceService.expand(principal.getUserId(), from, to));
    }

    @PostMapping
    public ResponseEntity<RoutineResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody RoutineSaveRequest request
    ) {
        log.info("POST /api/routines - userId={}, title={}", principal.getUserId(), request.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(routineService.create(principal.getUserId(), request));
    }

    @PutMapping("/{routineId}")
    public ResponseEntity<RoutineResponse> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long routineId,
            @Valid @RequestBody RoutineSaveRequest request
    ) {
        return ResponseEntity.ok(routineService.update(principal.getUserId(), routineId, request));
    }

    @DeleteMapping("/{routineId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long routineId
    ) {
        routineService.delete(principal.getUserId(), routineId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{routineId}/exceptions")
    public ResponseEntity<RoutineExceptionResponse> addException(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long routineId,
            @Valid @RequestBody RoutineExceptionSaveRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(routineService.addException(principal.getUserId(), routineId, request));
    }

    @PutMapping("/{routineId}/exceptions/{routineExceptionId}")
    public ResponseEntity<RoutineExceptionResponse> updateException(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long routineId,
            @PathVariable Long routineExceptionId,
            @Valid @RequestBody RoutineExceptionSaveRequest request
    ) {
        return ResponseEntity.ok(routineService.updateException(
                principal.getUserId(), routineId, routineExceptionId, request));
    }

    @DeleteMapping("/{routineId}/exceptions/{routineExceptionId}")
    public ResponseEntity<Void> deleteException(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long routineId,
            @PathVariable Long routineExceptionId
    ) {
        routineService.deleteException(principal.getUserId(), routineId, routineExceptionId);
        return ResponseEntity.noContent().build();
    }
}
