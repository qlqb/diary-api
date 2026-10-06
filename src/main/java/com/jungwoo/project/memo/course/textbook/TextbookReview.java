package com.jungwoo.project.memo.course.textbook;

import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.textbook.web.LookupResult;

import java.util.List;

/**
 * 프로젝트 교재 구역.
 *
 * <p>"강의계획서에 적힌 교재"(syllabusClues — 후보)와 "지금 쓰는 교재"(current)를 따로 보인다. 자료에서 다른 책을 찾았다고
 * 지금 교재가 바뀌지 않는다.
 *
 * @param state          목차 상태. NONE(교재 단서 없음) · TITLE_ONLY(어느 책인지는 있지만 목차 미확보) · TOC_FOUND(목차 확보)
 * @param nextAction     지금 할 수 있는 다음 행동 한 문장. 목차가 없어도 계획·학습은 진행된다고 함께 말한다
 * @param pending        아직 원문을 읽지 못한 연결 자료 수(분석 중·추출 전). "목차 없음"으로 확정하지 않은 자료다
 * @param lookup         웹에서 책·목차를 찾는 작업(없으면 null — 단서 없음·꺼짐)
 * @param unlinkedTocs   교재가 정해져 있는데 어느 책인지 적혀 있지 않아 저절로 쓰지 않은 업로드 목차
 * @param webLookupEnabled 교재 단서를 외부로 보내 찾는가
 */
public record TextbookReview(
        Long courseId,
        Current current,
        List<Candidate> candidates,
        Toc toc,
        String state,
        String nextAction,
        int topicCount,
        int pending,
        List<SyllabusClue> syllabusClues,
        Lookup lookup,
        List<TocResolver.Unlinked> unlinkedTocs,
        boolean webLookupEnabled
) {

    /**
     * 지금 교재 칸. source: USER(직접 적음) · MATERIAL(자료에서 찾아 적용) · WEB(웹에서 찾은 판으로 정함) · null.
     *
     * @param version 교재 판. 고칠 때 함께 보낸다(다르면 409)
     * @param web     WEB이면 그 판의 근거
     */
    public record Current(String title, String author, String publisher, String isbn, String edition,
                          String source, Long materialId, int version, WebSource web) {
        static Current of(Course course, WebSource web) {
            return new Current(course.getTextbookTitle(), course.getTextbookAuthor(), course.getTextbookPublisher(),
                    course.getTextbookIsbn(), course.getTextbookEdition(), course.getTextbookInfoSource(),
                    course.getTextbookInfoMaterialId(), course.getTextbookVersion() == null ? 0 : course.getTextbookVersion(),
                    web);
        }
    }

    /** 웹 근거 한 장(어느 페이지에서 언제 읽었나). */
    public record WebSource(Long revisionId, String site, String url, String fetchedAt, String isbn13,
                            String publishedDate) {
    }

    /** 자료 하나에서 찾은 서지 후보(판권면 이름표). 적용 전에는 교재 칸을 바꾸지 않는다. */
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

    /**
     * 강의계획서 등이 적은 교재(후보). source: RULE(규칙) · MODEL(모델이 줄을 짚고 서버가 원문에서 자름).
     *
     * @param sameAsCurrent 지금 교재와 같은 책이다
     */
    public record SyllabusClue(Long materialId, String filename, String role, String title, String author,
                               String publisher, String isbn, String edition, int unit, String quote, String source,
                               boolean sameAsCurrent) {
    }

    /**
     * 확보한 목차. NOT_FOUND면 entries는 비고, 책 이름만으로 만든 목차는 없다.
     *
     * @param kind     MATERIAL(올린 자료) · WEB(웹 페이지)
     * @param label    출처 한 줄(「파일」 목차 / 웹 목차(예스24 · 10/4 조회 · 페이지에 실린 목차 전체))
     * @param coverage WEB일 때 PAGE_FULL · PARTIAL · UNKNOWN
     */
    /**
     * @param unread 웹 목차 원문에서 목차 항목으로 읽지 못한 줄 수(머리 줄 제외). 조용히 버리지 않고 화면이 수를 보인다
     */
    public record Toc(String status, Long materialId, String filename, int entryCount, Integer fromUnit, Integer toUnit,
                      List<TextbookExtractor.TocEntry> entries, String kind, String label, String coverage,
                      String sourceUrl, String fetchedAt, int unread) {

        public Toc(String status, Long materialId, String filename, int entryCount, Integer fromUnit, Integer toUnit,
                   List<TextbookExtractor.TocEntry> entries, String kind, String label, String coverage,
                   String sourceUrl, String fetchedAt) {
            this(status, materialId, filename, entryCount, fromUnit, toUnit, entries, kind, label, coverage, sourceUrl,
                    fetchedAt, 0);
        }
    }

    /**
     * 웹 조회 작업.
     *
     * @param status   QUEUED·RUNNING(찾는 중) · FOUND · NEEDS_CHOICE(판 확인 필요) · BOOK_NO_TOC(책은 있으나 목차 없음) ·
     *                 NOT_FOUND · ACCESS_FAILED · FAILED · CLUE_CONFLICT(주교재 단서가 여럿)
     * @param query    외부로 보낸 단서 그대로
     * @param autoTidy 목차를 확보한 뒤 정리안을 자동으로: NONE · PENDING · ENQUEUED · WAITING_OPEN_PROPOSAL · DONE
     */
    public record Lookup(Long lookupId, String status, String clueOrigin, LookupResult.Query query, String searchedWith,
                         List<LookupResult.Edition> editions, List<LookupResult.Candidate> candidates,
                         List<LookupResult.Failure> failures, List<LookupResult.SyllabusOption> clueOptions,
                         String note, String errorCode, String createdAt, String finishedAt, String autoTidy,
                         Long chosenRevisionId, boolean reused) {
    }
}
