package com.jungwoo.project.memo.course.dto;

import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * 프로젝트 제목/분류/교재 수정 요청. 모두 선택이다.
 *
 * 교재 정보를 여기서 고칠 수 있어야 하는 이유: 지금까지 이 값을 채우는 경로는 자료 분석
 * 적용 하나뿐이었고, AI가 잘못 뽑으면 사용자가 손댈 방법이 없었다. 제목만으로는 어느 책인지
 * 알 수 없어서(같은 제목의 교재가 여럿이다) 저자·출판사·ISBN이 판정에 필요한데, 그 값이
 * 틀렸을 때 고칠 수 없으면 확인이라는 행위 자체가 성립하지 않는다.
 *
 * 교재 칸(2026-10-04): <b>JSON에 없는 칸은 그대로 두고</b>, 명시한 null·빈 문자열은 지운다. 제목만 바꾸는 요청이
 * 교재를 지우지 않게 하려고 어떤 칸이 왔는지 기록한다({@link #textbookFieldPresent}). 교재 칸을 하나라도 보내면
 * 화면이 본 교재 판(expectedTextbookVersion)도 보내야 한다 — 오래 열린 편집 창이 다른 곳에서 정한 교재를 덮지 않게.
 */
@Getter
@NoArgsConstructor
public class CourseUpdateRequest {

    @Size(max = 200)
    @Setter
    private String title;

    @Size(max = 50)
    @Setter
    private String groupLabel;

    @Size(max = 300)
    private String textbookTitle;

    @Size(max = 200)
    private String textbookAuthor;

    @Size(max = 200)
    private String textbookPublisher;

    @Size(max = 30)
    private String textbookIsbn;

    @Size(max = 100)
    private String textbookEdition;

    @Setter
    private Integer expectedTextbookVersion;

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    private final Set<String> present = new HashSet<>();

    public void setTextbookTitle(String v) {
        textbookTitle = v;
        present.add("title");
    }

    public void setTextbookAuthor(String v) {
        textbookAuthor = v;
        present.add("author");
    }

    public void setTextbookPublisher(String v) {
        textbookPublisher = v;
        present.add("publisher");
    }

    public void setTextbookIsbn(String v) {
        textbookIsbn = v;
        present.add("isbn");
    }

    public void setTextbookEdition(String v) {
        textbookEdition = v;
        present.add("edition");
    }

    /** title·author·publisher·isbn·edition 중 그 칸이 요청 JSON에 있었는가. */
    public boolean textbookFieldPresent(String field) {
        return present.contains(field);
    }

    public boolean anyTextbookFieldPresent() {
        return !present.isEmpty();
    }
}
