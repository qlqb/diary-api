package com.jungwoo.project.memo.execution;

import com.jungwoo.project.memo.execution.domain.ExecutionRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExecutionRecordMapper {
    void insert(ExecutionRecord record);
}
