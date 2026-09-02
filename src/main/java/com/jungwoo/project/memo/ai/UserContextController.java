package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.UserContextResponse;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 사용자 장기 컨텍스트 조회. 최소 조회 API만 둔다 — Context 전용 대형 관리 화면은 이번
 * 범위가 아니다. 향후 "장기 컨텍스트" 화면에서 이 DTO를 그대로 재사용할 수 있게 구성한다.
 *
 * 실제 상태 변경은 여기 없다 — /api/ai/context-suggestions/{id}/apply를 통해서만 일어난다.
 */
@Slf4j
@RestController
@RequestMapping("/api/contexts")
@RequiredArgsConstructor
public class UserContextController {

    private final UserContextService userContextService;

    @GetMapping
    public ResponseEntity<List<UserContextResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(userContextService.listActiveAndStale(principal.getUserId()));
    }
}
