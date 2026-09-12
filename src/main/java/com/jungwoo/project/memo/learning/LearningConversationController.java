package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.learning.dto.LearningMessageRequest;
import com.jungwoo.project.memo.learning.dto.LearningMessageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Learning Agent 개인과외 대화 컨트롤러.
 *
 * POST /api/learning/conversations                    새 대화 시작
 * GET  /api/learning/conversations/{id}/messages       대화 이력
 * POST /api/learning/conversations/{id}/messages       질문 전송(동기 응답)
 */
@Slf4j
@RestController
@RequestMapping("/api/learning/conversations")
@RequiredArgsConstructor
public class LearningConversationController {

    private final LearningConversationService learningConversationService;

    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@AuthenticationPrincipal UserPrincipal principal) {
        Long conversationId = learningConversationService.createConversation(principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("conversationId", conversationId));
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<AiMessage>> getMessages(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long conversationId
    ) {
        return ResponseEntity.ok(learningConversationService.getMessages(principal.getUserId(), conversationId));
    }

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<LearningMessageResponse> sendMessage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long conversationId,
            @Valid @RequestBody LearningMessageRequest request
    ) {
        log.info("POST learning/conversations/{}/messages - userId={}, topicId={}",
                conversationId, principal.getUserId(), request.getTopicId());
        return ResponseEntity.ok(learningConversationService.sendMessage(
                principal.getUserId(), conversationId, request.getTopicId(), request.getMessage()));
    }
}
