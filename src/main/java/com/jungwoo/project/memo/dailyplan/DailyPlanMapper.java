package com.jungwoo.project.memo.dailyplan;

import com.jungwoo.project.memo.dailyplan.domain.DailyPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

@Mapper
public interface DailyPlanMapper {

    void insert(DailyPlan dailyPlan);

    DailyPlan findByUserIdAndDate(
            @Param("userId") Long userId,
            @Param("planDate") LocalDate planDate
    );
}
