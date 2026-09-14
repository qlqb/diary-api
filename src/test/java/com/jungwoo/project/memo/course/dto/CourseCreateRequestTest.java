package com.jungwoo.project.memo.course.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/courses 요청 바디의 역직렬화 계약.
 *
 * 이 테스트가 있는 이유는 이 DTO에 @AllArgsConstructor가 붙어 있기 때문이다. 연결 제안
 * apply가 서버 내부에서 이 요청을 직접 조립하느라 붙인 것인데, 생성자를 추가하면 Jackson이
 * 바인딩 방식을 세터/필드에서 생성자 기반으로 조용히 바꿀 수 있다. 프로젝트 생성은 연결
 * 제안 밖의 기존 경로라 여기서 조용히 깨지면 원인 추적이 어렵다.
 *
 * 두 매퍼를 다 본다. 클래스패스에 Jackson 2(com.fasterxml)와 3(tools.jackson)이 함께
 * 올라와 있고, HTTP 메시지 컨버터가 쓰는 쪽은 3, 서비스에 주입되는 ObjectMapper 빈은 2다.
 * 파라미터 이름을 읽을 수 있는 3 쪽이 생성자를 암묵적 creator로 고를 여지가 더 크므로,
 * 둘 다 통과해야 "바인딩 방식이 바뀌지 않았다"고 말할 수 있다.
 */
class CourseCreateRequestTest {

    private static final String TITLE_ONLY = "{\"title\":\"운영체제\"}";
    private static final String BOTH_FIELDS = "{\"title\":\"운영체제\",\"groupLabel\":\"3학년 1학기\"}";

    private CourseCreateRequest readWithJackson3(String json) {
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue(json, CourseCreateRequest.class);
    }

    private CourseCreateRequest readWithJackson2(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, CourseCreateRequest.class);
    }

    @Test
    @DisplayName("title 하나만 온 요청이 그대로 바인딩된다 — groupLabel은 null로 남는다")
    void bindsTitleOnlyRequest() throws Exception {
        for (CourseCreateRequest request : java.util.List.of(
                readWithJackson3(TITLE_ONLY), readWithJackson2(TITLE_ONLY))) {
            assertThat(request.getTitle()).isEqualTo("운영체제");
            assertThat(request.getGroupLabel()).isNull();
        }
    }

    @Test
    @DisplayName("두 필드가 다 온 요청도 그대로 바인딩된다")
    void bindsRequestWithGroupLabel() throws Exception {
        for (CourseCreateRequest request : java.util.List.of(
                readWithJackson3(BOTH_FIELDS), readWithJackson2(BOTH_FIELDS))) {
            assertThat(request.getTitle()).isEqualTo("운영체제");
            assertThat(request.getGroupLabel()).isEqualTo("3학년 1학기");
        }
    }

    @Test
    @DisplayName("빈 객체도 예외 없이 바인딩된다 — 필수값 검사는 @NotBlank가 맡는다")
    void bindsEmptyObject() throws Exception {
        for (CourseCreateRequest request : java.util.List.of(
                readWithJackson3("{}"), readWithJackson2("{}"))) {
            assertThat(request.getTitle()).isNull();
            assertThat(request.getGroupLabel()).isNull();
        }
    }

    @Test
    @DisplayName("서버 내부에서 조립하는 경로(연결 제안 apply)도 그대로 동작한다")
    void buildsFromConstructor() {
        CourseCreateRequest request = new CourseCreateRequest("운영체제", null);

        assertThat(request.getTitle()).isEqualTo("운영체제");
        assertThat(request.getGroupLabel()).isNull();
    }
}
