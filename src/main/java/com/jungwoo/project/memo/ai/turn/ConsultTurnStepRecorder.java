package com.jungwoo.project.memo.ai.turn;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Component;

/**
 * 상담 턴 단계 기록(consult_turn_steps). 실패 위치를 찾기 위한 기록이며 묶음 복원에는 쓰지 않는다. 기록 실패는 턴을 실패시키지 않는다.
 * 턴 트랜잭션 밖에서 바로 쓴다 — 턴이 실패해도 어디까지 갔는지 남아야 한다.
 */
@Slf4j
@Component
public class ConsultTurnStepRecorder {

    private static final int MAX_TEXT = 2000;

    @Mapper
    public interface StepMapper {
        @Insert("INSERT INTO consult_turn_steps (user_id, conversation_id, request_message_id, seq, kind, name, args_json, "
                + "result_code, result_text, latency_ms, input_tokens, output_tokens) VALUES (#{userId}, #{conversationId}, "
                + "#{requestMessageId}, #{seq}, #{kind}, #{name}, #{args}, #{code}, #{text}, #{latency}, #{inputTokens}, #{outputTokens})")
        void insert(@Param("userId") Long userId, @Param("conversationId") Long conversationId,
                    @Param("requestMessageId") Long requestMessageId, @Param("seq") int seq, @Param("kind") String kind,
                    @Param("name") String name, @Param("args") String args, @Param("code") String code,
                    @Param("text") String text, @Param("latency") Integer latency, @Param("inputTokens") Integer inputTokens,
                    @Param("outputTokens") Integer outputTokens);
    }

    private final StepMapper mapper;

    public ConsultTurnStepRecorder(StepMapper mapper) {
        this.mapper = mapper;
    }

    public void record(TurnWorkspace ws, int seq, String kind, String name, String args, String code, String text,
                       Long latencyMs, Integer inputTokens, Integer outputTokens) {
        try {
            mapper.insert(ws.userId(), ws.conversationId(), ws.requestMessageId(), seq, kind, name, cut(args), code, cut(text),
                    latencyMs == null ? null : (int) Math.min(Integer.MAX_VALUE, latencyMs), inputTokens, outputTokens);
        } catch (RuntimeException e) {
            log.warn("상담 단계 기록 실패(턴은 계속): kind={}, name={}, {}", kind, name, e.getClass().getSimpleName());
        }
    }

    private static String cut(String s) {
        return s == null ? null : s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }
}
