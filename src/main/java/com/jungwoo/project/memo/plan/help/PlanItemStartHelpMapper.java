package com.jungwoo.project.memo.plan.help;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlanItemStartHelpMapper {

    void insert(PlanItemStartHelp help);

    /** 제안 항목에 이어진 가장 최근 도움. 초안·확정·남은 분량이 같은 도움을 본다. */
    PlanItemStartHelp findLatestByProposalItem(@Param("proposalItemId") Long proposalItemId, @Param("userId") Long userId);

    /** 직접 만든 항목(제안 원본 없음)의 가장 최근 도움. */
    PlanItemStartHelp findLatestByExecutionItem(@Param("executionItemId") Long executionItemId, @Param("userId") Long userId);
}
