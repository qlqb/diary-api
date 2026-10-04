package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import com.jungwoo.project.memo.course.textbook.TextbookService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 강의계획서에 A가 적혀 있어도 사용자가 정한 B가 기준이라는 것, 그리고 교재가 바뀐 혼합 정리에서
 * 기존 항목을 건드리지 않고 B의 장·절만 더한다는 것이 정리 입력에 실리는지 본다.
 */
class ProjectTidyTextbookPromptTest {

    private final ProjectTidyAnalyzer analyzer = new ProjectTidyAnalyzer(null, null, null, null);

    private static Course course(String source) {
        Course course = new Course();
        course.setTitle("자료구조");
        course.setTextbookTitle("합성 교재 B");
        course.setTextbookPublisher("합성출판");
        course.setTextbookInfoSource(source);
        return course;
    }

    private static TextbookService.TocSnapshot toc() {
        return new TextbookService.TocSnapshot(1L, "toc.pdf", "h", null, null,
                List.of(new TextbookExtractor.TocEntry(0, "1장", "배열", 10, 1)));
    }

    private static ProjectTidyInputBuilder.Input input(Course course, String switchedFrom) {
        return new ProjectTidyInputBuilder.Input(course, List.of(), List.of(), List.of(), Map.of(), List.of(), null,
                new ProjectTidyInputBuilder.Guidance(null, List.of(), toc(), List.of(), switchedFrom));
    }

    @Test
    void 정해진_교재는_자료에_적힌_교재보다_우선한다고_싣는다() {
        String prompt = analyzer.buildUserPrompt(input(course("USER"), null));

        assertThat(prompt).contains("교재: ").contains("합성 교재 B")
                .contains("자료에 다른 교재가 적혀 있어도 이 교재와 [교재 목차]가 기준이다")
                .doesNotContain("[교재가 바뀌었다]");
    }

    @Test
    void 출처가_없는_교재는_우선한다고_말하지_않는다() {
        String prompt = analyzer.buildUserPrompt(input(course(null), null));

        assertThat(prompt).contains("합성 교재 B").doesNotContain("이 교재와 [교재 목차]가 기준이다");
    }

    @Test
    void 교재가_바뀌었으면_혼합_정리에도_바뀐_교재_규칙을_싣는다() {
        String prompt = analyzer.buildUserPrompt(input(course("WEB"), "isbn:9790000000000"));

        assertThat(prompt).contains("[교재가 바뀌었다]").contains("ADD(tocLine 필수)")
                .contains("지우거나 이름을 바꾸거나");
    }
}
