package com.jungwoo.project.memo.course.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프로젝트 제목/분류/교재 수정 요청. 모두 선택이며, 비워 보내면 그 값을 지운다.
 *
 * 교재 정보를 여기서 고칠 수 있어야 하는 이유: 지금까지 이 값을 채우는 경로는 자료 분석
 * 적용 하나뿐이었고, AI가 잘못 뽑으면 사용자가 손댈 방법이 없었다. 제목만으로는 어느 책인지
 * 알 수 없어서(같은 제목의 교재가 여럿이다) 저자·출판사·ISBN이 판정에 필요한데, 그 값이
 * 틀렸을 때 고칠 수 없으면 확인이라는 행위 자체가 성립하지 않는다.
 */
@Getter
@NoArgsConstructor
public class CourseUpdateRequest {

    @Size(max = 200)
    private String title;

    @Size(max = 50)
    private String groupLabel;

    @Size(max = 200)
    private String textbookTitle;

    @Size(max = 100)
    private String textbookAuthor;

    @Size(max = 100)
    private String textbookPublisher;

    @Size(max = 30)
    private String textbookIsbn;
}
