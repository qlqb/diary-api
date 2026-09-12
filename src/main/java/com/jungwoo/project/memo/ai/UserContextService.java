package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.ai.dto.UserContextResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 사용자 장기 컨텍스트 조회 전용(읽기만). 실제 상태 변경(ADD/SUPERSEDE/MARK_STALE/ARCHIVE/
 * CONFIRM)은 전부 ContextChangeSuggestionService.apply()를 통해서만 일어난다 — 이 서비스는
 * 쓰기 메서드를 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class UserContextService {

    private final UserContextMapper userContextMapper;

    /** ACTIVE/STALE만 반환한다(SUPERSEDED/ARCHIVED는 이력일 뿐 현재 화면에 노출하지 않는다). */
    @Transactional(readOnly = true)
    public List<UserContextResponse> listActiveAndStale(Long userId) {
        return userContextMapper.findActiveAndStaleByUserId(userId, null).stream()
                .map(this::toResponse)
                .toList();
    }

    private UserContextResponse toResponse(UserContext context) {
        return UserContextResponse.builder()
                .contextId(context.getContextId())
                .content(context.getContent())
                .status(context.getStatus())
                .sourceType(context.getSourceType())
                .supersedesContextId(context.getSupersedesContextId())
                .confirmedAt(context.getConfirmedAt())
                .createdAt(context.getCreatedAt())
                .updatedAt(context.getUpdatedAt())
                .build();
    }
}
