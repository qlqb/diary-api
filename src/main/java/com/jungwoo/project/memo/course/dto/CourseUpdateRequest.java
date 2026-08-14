package com.jungwoo.project.memo.course.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프로젝트 제목/분류 수정 요청. 둘 다 선택이며, groupLabel을 비워 보내면 분류를 지운다.
 */
@Getter
@NoArgsConstructor
public class CourseUpdateRequest {

    @Size(max = 200)
    private String title;

    @Size(max = 50)
    private String groupLabel;
}
