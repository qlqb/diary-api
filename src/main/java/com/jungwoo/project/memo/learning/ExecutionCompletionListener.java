package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.execution.domain.ExecutionItemCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 실행 완료 -> 학습 피드백 연결 지점. execution 패키지는 이 리스너의 존재를 모른다
 * (ExecutionItemCompletedEvent만 발행한다) — 학습 기능이 없어도 실행 완료 흐름은 그대로 동작한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionCompletionListener {

    private final TopicService topicService;

    @EventListener
    public void onExecutionItemCompleted(ExecutionItemCompletedEvent event) {
        if (event.topicId() == null) {
            return;
        }
        topicService.recordExecutionCompleted(event.userId(), event.topicId(), event.executionItemId());
    }
}
