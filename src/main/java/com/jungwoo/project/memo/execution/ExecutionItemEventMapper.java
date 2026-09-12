package com.jungwoo.project.memo.execution;

import com.jungwoo.project.memo.execution.domain.ExecutionItemEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ExecutionItemEventMapper {
    void insert(ExecutionItemEvent event);

    /**
     * 한 조각의 조정 사건 전부, 일어난 순서대로. 계획 근거 조회가 "적용된 뒤에 이 조각이
     * 바뀌었는가"를 사건으로 읽는다 — 현재 값과 제안 값을 비교하면 서버 배치(롤링 배치)와
     * 사용자 수정을 가를 수 없지만, 사건에는 주체(actor_type)가 남아 있다.
     */
    List<ExecutionItemEvent> findByExecutionItemIdAndUserId(@Param("executionItemId") Long executionItemId,
                                                           @Param("userId") Long userId);
}
