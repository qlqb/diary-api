package com.jungwoo.project.memo.planitem;

import com.jungwoo.project.memo.planitem.domain.PlanItemEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface PlanItemEventMapper {

    void insert(PlanItemEvent event);

    /**
     * 기간별 이벤트 조회. (startDate 이상, endDate 이하)
     * 1차-B 주간 회고 집계의 기반 쿼리.
     */
    List<PlanItemEvent> findByUserIdAndPeriod(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
