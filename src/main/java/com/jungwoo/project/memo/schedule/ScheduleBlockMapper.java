package com.jungwoo.project.memo.schedule;

import com.jungwoo.project.memo.schedule.domain.ScheduleBlock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ScheduleBlockMapper {

    void insert(ScheduleBlock block);

    ScheduleBlock findByIdAndUserId(
            @Param("scheduleBlockId") Long scheduleBlockId,
            @Param("userId") Long userId
    );

    List<ScheduleBlock> findByUserIdAndDate(
            @Param("userId") Long userId,
            @Param("blockDate") LocalDate blockDate
    );

    void update(ScheduleBlock block);
}
