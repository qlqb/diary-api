package com.jungwoo.project.memo.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프로젝트 생성 요청.
 *
 * title 하나만 필수다 — 자료를 먼저 가지고 있어야 프로젝트를 만들 수 있는 구조로 만들지
 * 않는다. groupLabel은 사용자가 목록을 묶어 보기 위한 선택 분류다.
 */
@Getter
@NoArgsConstructor
// 서버 내부에서 프로젝트를 만드는 경로(연결 제안 apply)가 이 요청을 직접 조립한다.
// 세터를 열지 않는 것은 의도다 — 역직렬화 뒤에 필드가 바뀌는 자리를 만들지 않는다.
@AllArgsConstructor
public class CourseCreateRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(max = 50)
    private String groupLabel;
}
