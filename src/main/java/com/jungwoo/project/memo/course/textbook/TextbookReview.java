package com.jungwoo.project.memo.course.textbook;

import com.jungwoo.project.memo.course.domain.Course;

import java.util.List;

/**
 * 프로젝트 교재 확인 화면.
 *
 * @param state      NONE(교재 단서 없음) · TITLE_ONLY(어느 책인지는 있지만 목차 미확보) · TOC_FOUND(목차 확보)
 * @param nextAction 지금 할 수 있는 다음 행동 한 문장. 목차가 없어도 계획·학습은 진행된다고 함께 말한다
 * @param pending    아직 원문을 읽지 못한 연결 자료 수(분석 중·추출 전). "목차 없음"으로 확정하지 않은 자료다
 */
public record TextbookReview(
        Long courseId,
        Current current,
        List<Candidate> candidates,
        Toc toc,
        String state,
        String nextAction,
        int topicCount,
        int pending
) {

    /** 지금 교재 칸. source: USER(직접 적음) · MATERIAL(자료에서 찾아 적용) · null. */
    public record Current(String title, String author, String publisher, String isbn, String edition,
                          String source, Long materialId) {
        static Current of(Course course) {
            return new Current(course.getTextbookTitle(), course.getTextbookAuthor(), course.getTextbookPublisher(),
                    course.getTextbookIsbn(), course.getTextbookEdition(), course.getTextbookInfoSource(),
                    course.getTextbookInfoMaterialId());
        }
    }

    /** 자료 하나에서 찾은 서지 후보. 적용 전에는 교재 칸을 바꾸지 않는다. */
    public record Candidate(Long materialId, String filename, String materialType, List<FieldCandidate> fields) {
    }

    /**
     * @param unit     원문 단위(PDF 쪽·슬라이드 번호)
     * @param quote    그 값을 읽은 원문 줄 그대로
     * @param current  지금 교재 칸 값
     * @param same     지금 값과 같다(적용할 필요 없음)
     */
    public record FieldCandidate(String field, String value, int unit, String quote, String current, boolean same) {
    }

    /** 확보한 목차. NOT_FOUND면 entries는 비고, 책 이름만으로 만든 목차는 없다. */
    public record Toc(String status, Long materialId, String filename, int entryCount, Integer fromUnit, Integer toUnit,
                      List<TextbookExtractor.TocEntry> entries) {
    }
}
