package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiProposalItemMapper {

    void insert(AiProposalItem item);

    /** 제안 항목 단건. 「자세히」 안내가 항목의 근거(evidence_json)를 읽을 때 쓴다. */
    AiProposalItem findByIdAndUserId(@Param("proposalItemId") Long proposalItemId, @Param("userId") Long userId);

    List<AiProposalItem> findByProposalIdAndUserId(
            @Param("proposalId") Long proposalId,
            @Param("userId") Long userId
    );

    /**
     * 이 실행 조각을 만든 제안 항목. 적용된 항목에서 원래 제안·생성 회차로 되짚는 유일한
     * 경로다 — 새 연결 컬럼을 만들지 않고 이미 있는 created_item_id를 쓴다.
     *
     * <p>조정 제안(REDUCE/MOVE)도 이 컬럼에 "바뀐 대상"을 남기므로 한 실행 조각이 여러
     * 제안 항목에 걸릴 수 있다. 최근 것부터 돌려주고, 고르는 것은 호출부의 몫이다.
     */
    List<AiProposalItem> findByCreatedItemIdAndUserId(
            @Param("createdItemId") Long createdItemId,
            @Param("userId") Long userId
    );

    /**
     * 근거의 <b>상태만</b> 바꾼다. 근거 자체는 덮어쓰지 않는다.
     *
     * <p>사용자가 항목을 고쳤을 때 "이 근거는 수정 전 제안의 것"이라고 표시하기 위한
     * 경로다. 서버만 부른다 — 클라이언트 요청에는 이 필드가 없다.
     */
    int updateEvidenceJson(
            @Param("proposalItemId") Long proposalItemId,
            @Param("userId") Long userId,
            @Param("evidenceJson") String evidenceJson
    );

    int updateAfterApply(
            @Param("proposalItemId") Long proposalItemId,
            @Param("userId") Long userId,
            @Param("status") AiProposalItemStatus status,
            @Param("editedPayload") String editedPayload,
            @Param("createdItemType") String createdItemType,
            @Param("createdItemId") Long createdItemId,
            @Param("respondedAt") LocalDateTime respondedAt
    );
}
