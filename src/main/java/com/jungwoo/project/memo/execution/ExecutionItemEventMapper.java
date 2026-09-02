package com.jungwoo.project.memo.execution;

import com.jungwoo.project.memo.execution.domain.ExecutionItemEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExecutionItemEventMapper {
    void insert(ExecutionItemEvent event);
}
