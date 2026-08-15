package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.dto.AiConversationResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiConversationMapper {

    void insert(AiConversation conversation);

    AiConversation findByIdAndUserId(Long conversationId, Long userId);

    /**
     * 이 사용자의 대화 목록. 메시지가 하나도 없는 대화(첫 메시지 전송 전 상태)와 사용자가
     * 삭제한 대화(status=ARCHIVED)는 제외한다 — 목록에는 실제로 대화가 시작됐고 아직 지우지
     * 않은 것만 보인다. title은 원문 그대로(정리·자르기 전) 반환하며, 서비스 계층에서 다듬는다.
     * 마지막 메시지 시각 내림차순.
     *
     * @param courseId            지정하면 그 프로젝트 대화만
     * @param onlyWithoutCourse   true면 프로젝트에 속하지 않은 대화만
     */
    List<AiConversationResponse> findSummariesByUserId(@Param("userId") Long userId,
                                                        @Param("courseId") Long courseId,
                                                        @Param("onlyWithoutCourse") boolean onlyWithoutCourse);

    /**
     * 대화 상태 변경. 사용자가 대화를 삭제할 때 ARCHIVED로 내리는 데 쓴다 —
     * 행을 지우지 않으므로 메시지·제안·이미 반영된 장기 컨텍스트가 그대로 남는다.
     */
    int updateStatus(@Param("conversationId") Long conversationId,
                      @Param("userId") Long userId,
                      @Param("status") String status);

    /** 대화방 잠금 확인/획득 트랜잭션에서 행 잠금을 건다. MariaDB: SELECT ... FOR UPDATE. */
    AiConversation findByIdAndUserIdForUpdate(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    void touchUpdatedAt(Long conversationId, Long userId);

    /** SELECT ... FOR UPDATE로 이미 행을 잠근 뒤에만 호출한다 — 그 잠금이 동시 요청을 직렬화한다. */
    void acquireActiveRequest(@Param("conversationId") Long conversationId,
                               @Param("userId") Long userId,
                               @Param("requestMessageId") Long requestMessageId);

    /**
     * 진행 표시 해제. active_request_message_id가 지금도 requestMessageId와 같을 때만 지운다 —
     * 더 늦게 끝난 이전 요청이 새 요청의 진행 표시를 잘못 지우는 것을 막는다.
     */
    int releaseActiveRequest(@Param("conversationId") Long conversationId,
                              @Param("userId") Long userId,
                              @Param("requestMessageId") Long requestMessageId);
}
