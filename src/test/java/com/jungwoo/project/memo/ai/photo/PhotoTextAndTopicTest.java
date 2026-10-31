package com.jungwoo.project.memo.ai.photo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사진 읽기 결과 정리(개인 정보 표지·길이·쪽)와 단원 추정(번호·제목·쪽 규칙의 교집합), 사진을 가리키는 말의 단원.
 * 단원 추정은 서버 규칙이다 — 모델이 정하지 않는다.
 */
class PhotoTextAndTopicTest {

    private static final List<PhotoTopicMatcher.Candidate> TOC = List.of(
            new PhotoTopicMatcher.Candidate(501L, "Unit 1 What's your name?", "교재 p.6", 1),
            new PhotoTopicMatcher.Candidate(502L, "Unit 2 I'm doing my homework right now", "교재 p.12", 2),
            new PhotoTopicMatcher.Candidate(503L, "Unit 3 I have to make hotel reservations", "교재 p.18", 3),
            new PhotoTopicMatcher.Candidate(504L, "Unit 4 Did you have a good weekend?", "교재 p.30", 4),
            new PhotoTopicMatcher.Candidate(510L, "Unit 10 What's your name?", "교재 p.66", 10));

    @Test
    void 정리는_전화_이메일_주민번호_꼴을_가리고_길이를_자르고_쪽은_숫자만() {
        var raw = new TextbookPhotoReader.Raw(true, "- 24 -", List.of("Unit 3 I have to make hotel reservations", "  "),
                "Call me at 010-1234-5678 or a.b@example.com\u0007\nID 900101-1234567\n" + "x".repeat(13_000),
                "정답: have to");

        PhotoTextSanitizer.Clean clean = PhotoTextSanitizer.clean(raw);

        assertThat(clean.printedPage()).isEqualTo(24);
        assertThat(clean.headings()).containsExactly("Unit 3 I have to make hotel reservations");
        assertThat(clean.text()).doesNotContain("010-1234-5678", "a.b@example.com", "900101-1234567", "\u0007")
                .contains(PhotoTextSanitizer.REDACTED).hasSize(PhotoTextSanitizer.MAX_TEXT);
        assertThat(clean.truncated()).isTrue();
        assertThat(clean.unitText()).contains("[손글씨 — 누가 썼는지 확인되지 않음]\n정답: have to");
        assertThat(PhotoTextSanitizer.page("p.123")).isEqualTo(123);
        assertThat(PhotoTextSanitizer.page("24쪽 3번")).isNull();
    }

    @Test
    void 단원_번호가_있으면_같은_번호의_단원이고_Unit_1은_Unit_10이_아니다() {
        assertThat(PhotoTopicMatcher.match(TOC, List.of("Unit 3"), null)).isEqualTo(503L);
        assertThat(PhotoTopicMatcher.match(TOC, List.of("UNIT 1 What's your name?"), null)).isEqualTo(501L);
        assertThat(PhotoTopicMatcher.match(TOC, List.of("Lesson 10"), null)).isEqualTo(510L);
    }

    @Test
    void 번호_없는_제목만으로도_찾지만_같은_제목이_둘이면_쪽으로_가른다() {
        assertThat(PhotoTopicMatcher.match(TOC, List.of("I have to make hotel reservations"), null)).isEqualTo(503L);
        // "What's your name?"은 Unit 1과 Unit 10 둘 다 — 제목만으로는 모른다.
        assertThat(PhotoTopicMatcher.match(TOC, List.of("What's your name?"), null)).isNull();
        assertThat(PhotoTopicMatcher.match(TOC, List.of("What's your name?"), 68)).isEqualTo(510L);
        // 같은 낱말이 두 번 나오는 제목도 비교할 수 있다(예외 없이).
        assertThat(PhotoTopicMatcher.match(TOC, List.of("What do you do? What do you do?"), null)).isNull();
    }

    @Test
    void 쪽만_있으면_목차_쪽_범위로_찾고_규칙끼리_다르면_연결하지_않는다() {
        assertThat(PhotoTopicMatcher.match(TOC, List.of(), 24)).isEqualTo(503L);
        assertThat(PhotoTopicMatcher.match(TOC, List.of(), 3)).isNull(); // 첫 단원보다 앞
        assertThat(PhotoTopicMatcher.match(TOC, List.of(), 200)).isNull(); // 마지막 단원 범위를 한참 지남
        assertThat(PhotoTopicMatcher.match(TOC, List.of("Unit 4"), 24)).isNull(); // 번호는 4, 쪽은 3단원
        assertThat(PhotoTopicMatcher.match(TOC, List.of(), null)).isNull();
    }

    @Test
    void 사진을_가리키는_말이고_사진이_모두_같은_단원일_때만_그_단원을_쓴다() {
        var unit3 = new ConsultPhotoContext.ActivePhoto(91L, 503L, "Unit 3", true);
        var unit3b = new ConsultPhotoContext.ActivePhoto(92L, 503L, "Unit 3", false);
        var unit4 = new ConsultPhotoContext.ActivePhoto(93L, 504L, "Unit 4", false);
        var none = new ConsultPhotoContext.ActivePhoto(94L, null, null, false);

        assertThat(ConsultPhotoContext.reference(List.of(unit3, unit3b), "이 문제 모르겠어"))
                .isEqualTo(new ConsultPhotoContext.Reference(503L, 91L, List.of(91L, 92L)));
        assertThat(ConsultPhotoContext.reference(List.of(unit3), "2번 왜 틀렸어?")).isNotNull();
        assertThat(ConsultPhotoContext.reference(List.of(unit3), "have to 문장이 어려워")).isNull(); // 사진을 가리키지 않음
        assertThat(ConsultPhotoContext.reference(List.of(unit3, unit4), "이거 모르겠어")).isNull(); // 어느 단원인지 모름
        assertThat(ConsultPhotoContext.reference(List.of(none), "이거 모르겠어")).isNull();
        assertThat(ConsultPhotoContext.pinned(List.of(unit3, none)))
                .containsEntry(91L, "Unit 3(사진 단원 추정)").containsEntry(94L, "");
    }
}
