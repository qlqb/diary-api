package com.jungwoo.project.memo.execution;

import com.jungwoo.project.memo.execution.dto.ExecutionRecordResponse;
import com.jungwoo.project.memo.execution.domain.ExecutionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ExecutionRecordMapper {

    void insert(ExecutionRecord record);

    /**
     * 기록 화면용 조회. 기록 자체에는 제목이 없으므로(무엇을 했는지는 실행 조각이 안다)
     * execution_items를 조인해 제목·날짜·프로젝트를 함께 내려준다. 계획 밖 결과
     * (execution_item_id IS NULL)도 빠지지 않도록 LEFT JOIN을 쓴다.
     */
    List<ExecutionRecordResponse> findByUserIdAndDateRange(@Param("userId") Long userId,
                                                            @Param("startDate") LocalDate startDate,
                                                            @Param("endDate") LocalDate endDate);

    /**
     * 주어진 실행 조각들의 기록 전체. 회고가 항목별로 recorded_at DESC 최신 1건을 고르고
     * 건수(recordCount)도 함께 내려주기 위해 전량을 가져온다 — 항목 수가 계획 하나 분량
     * (최대 30개)이므로 SQL에서 윈도우 함수로 1건만 뽑는 복잡도를 들일 이유가 없다.
     */
    List<ExecutionRecord> findByExecutionItemIds(
            @Param("userId") Long userId,
            @Param("executionItemIds") List<Long> executionItemIds
    );
}
