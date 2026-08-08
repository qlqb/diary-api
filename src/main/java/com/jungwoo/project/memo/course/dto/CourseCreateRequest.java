package com.jungwoo.project.memo.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CourseCreateRequest {

    @NotBlank
    @Size(max = 200)
    private String title;
}
