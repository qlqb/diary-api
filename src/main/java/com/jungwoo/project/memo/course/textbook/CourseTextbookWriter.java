package com.jungwoo.project.memo.course.textbook;

import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 과목의 "지금 쓰는 교재" 칸을 쓰는 유일한 길.
 *
 * <p>사용자 직접 편집 · 자료 후보 적용 · 웹에서 찾은 판 선택 · 정리안 적용과 함께 기록 · 옛 분석 적용이 모두 이리로 온다.
 * <ul>
 *   <li>행을 잠그고 화면이 본 판(expectedVersion)과 대조한다. 다르면 409 — 다른 탭·늦은 작업이 사용자가 고친 값을 덮지 않는다.</li>
 *   <li>쓸 때마다 판(textbook_version)이 1 오른다. 교재 조회·정리안의 목차 근거가 이 판을 들고 있다가 결과를 저장·적용할
 *       때 대조한다 — 늦게 끝난 옛 교재의 검색 결과가 새 교재를 덮지 않는다.</li>
 *   <li>교재 식별(ISBN·제목·판)이 바뀌면 사용자가 이었던 목차 자료 연결을 푼다. 저자·출판사 표기만 고친 경우는 같은 책이라
 *       웹 판 근거도 유지한다.</li>
 *   <li>커밋 뒤 {@link TextbookChangedEvent}를 낸다 — 교재 조회가 새 판으로 다시 맞춰진다(같은 트랜잭션에서 외부 조회를 하지
 *       않는다).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseTextbookWriter {

    public static final String SOURCE_USER = "USER";
    public static final String SOURCE_MATERIAL = "MATERIAL";
    public static final String SOURCE_WEB = "WEB";

    private final CourseMapper courseMapper;
    private final com.jungwoo.project.memo.learning.CourseTopicMapper topicMapper;
    private final ApplicationEventPublisher events;
    private final TextbookRefService refService;

    public record Values(String title, String author, String publisher, String isbn, String edition) {

        public static Values of(Course c) {
            return new Values(c.getTextbookTitle(), c.getTextbookAuthor(), c.getTextbookPublisher(), c.getTextbookIsbn(),
                    c.getTextbookEdition());
        }

        public boolean isEmpty() {
            return blank(title) && blank(author) && blank(publisher) && blank(isbn) && blank(edition);
        }

        Values normalized() {
            return new Values(cut(title, 300), cut(author, 200), cut(publisher, 200), cut(isbn, 50), cut(edition, 100));
        }
    }

    /** 잠근 뒤의 과목. 호출자가 다른 대조(예: 화면이 본 칸 값)를 더 하고 싶을 때 쓴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Course lock(Long userId, Long courseId) {
        Course course = courseMapper.findByIdAndUserIdForUpdate(courseId, userId);
        if (course == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        return course;
    }

    /**
     * 교재 칸을 next로 바꾼다. 같은 값이면 아무것도 하지 않고 false.
     *
     * @param locked          {@link #lock}으로 잠근 과목(같은 트랜잭션)
     * @param expectedVersion 화면이 본 판. null이면 대조하지 않는다 — 서버 안쪽 경로(정리안 적용처럼 이미 다른 근거로
     *                        판을 대조한 경우)만 null을 넘긴다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean write(Course locked, Integer expectedVersion, Values next, String source, Long materialId,
                         Long webRevisionId) {
        int current = locked.getTextbookVersion() == null ? 0 : locked.getTextbookVersion();
        if (expectedVersion != null && expectedVersion != current) {
            throw new ConflictException(ErrorCode.TEXTBOOK_VERSION_CHANGED);
        }
        Values before = Values.of(locked).normalized();
        Values after = next.normalized();
        boolean sameValues = before.equals(after);
        boolean sameSource = Objects.equals(source, locked.getTextbookInfoSource())
                && Objects.equals(materialId, locked.getTextbookInfoMaterialId())
                && Objects.equals(webRevisionId, locked.getTextbookWebRevisionId());
        if (sameValues && (sameSource || SOURCE_USER.equals(source))) {
            return false;
        }
        // 있던 ISBN·판 표기를 바꾸거나 지우면 다른 판이다(빈 칸을 채우는 것은 같은 책의 식별 보강).
        boolean sameBook = BookKey.sameBook(before.title(), before.isbn(), before.edition(),
                after.title(), after.isbn(), after.edition()) && !editionChanged(before, after);
        Long revision = webRevisionId;
        if (SOURCE_USER.equals(source) && sameBook && revision == null && sameEdition(before, after)) {
            // 표기(제목·저자·출판사)만 고쳤다 — 웹에서 확인한 판 근거는 여전히 이 판의 것이다.
            // ISBN이나 판 표기를 바꾸거나 지우면 다른 판일 수 있으니 근거를 비운다(옛 판의 목차를 쓰지 않게).
            revision = locked.getTextbookWebRevisionId();
        }
        String oldKey = BookKey.of(before.title(), before.isbn(), before.edition());
        String newKey = BookKey.of(after.title(), after.isbn(), after.edition());
        int rows = courseMapper.writeTextbook(locked.getCourseId(), locked.getUserId(), current, after.title(),
                after.author(), after.publisher(), after.isbn(), after.edition(), source,
                SOURCE_MATERIAL.equals(source) ? materialId : null, revision, !sameBook, newKey);
        if (rows != 1) {
            throw new ConflictException(ErrorCode.TEXTBOOK_VERSION_CHANGED);
        }
        if (sameBook && oldKey != null && newKey != null && !oldKey.equals(newKey)) {
            // 같은 책의 식별을 보강했다(예: 제목만 알던 책에 ISBN). 그 책의 목차에서 온 항목을 이전 교재로 오판하지 않게 열쇠를 옮긴다.
            topicMapper.updateSourceTextbookKey(locked.getCourseId(), locked.getUserId(), oldKey, newKey);
        }
        // 이 책의 ISBN 키·제목 키를 같은 교재 식별자로(학습 이벤트는 키가 아니라 식별자를 가리킨다 — 설계 20번 §5.1).
        // 같은 책의 식별을 바꾼 것이면 이전 키의 식별자를 잇는다(판을 채워 제목 키가 바뀌어도 같은 교재).
        if (sameBook) {
            refService.continueBook(locked.getUserId(),
                    TextbookRefService.keysOf(before.title(), before.isbn(), before.edition()),
                    after.title(), after.isbn(), after.edition());
        } else {
            refService.ensureBook(locked.getUserId(), after.title(), after.isbn(), after.edition());
        }
        locked.setTextbookVersion(current + 1);
        log.info("교재 칸 변경: courseId={}, source={}, sameBook={}, version={}", locked.getCourseId(), source, sameBook,
                current + 1);
        events.publishEvent(new TextbookChangedEvent(locked.getUserId(), locked.getCourseId(), current + 1));
        return true;
    }

    /** 있던 ISBN이나 판 표기가 바뀌었거나 지워졌다. 비어 있던 칸을 채운 것은 바뀐 것이 아니다. */
    static boolean editionChanged(Values before, Values after) {
        return replacedOrRemoved(isbnDigits(before.isbn()), isbnDigits(after.isbn()))
                || replacedOrRemoved(editionText(before.edition()), editionText(after.edition()));
    }

    private static boolean replacedOrRemoved(String before, String after) {
        return before != null && !before.equals(after);
    }

    /** ISBN(숫자·X만)과 판 표기가 그대로다. 한쪽만 비어 있어도 다르다고 본다. */
    static boolean sameEdition(Values before, Values after) {
        return Objects.equals(isbnDigits(before.isbn()), isbnDigits(after.isbn()))
                && Objects.equals(editionText(before.edition()), editionText(after.edition()));
    }

    private static String isbnDigits(String isbn) {
        if (isbn == null) {
            return null;
        }
        String d = isbn.replaceAll("[^0-9Xx]", "").toUpperCase();
        return d.isEmpty() ? null : d;
    }

    private static String editionText(String edition) {
        if (edition == null) {
            return null;
        }
        String e = edition.replaceAll("\\s+", "").toLowerCase();
        return e.isEmpty() ? null : e;
    }

    /** "이 목차가 지금 교재의 것"이라는 사용자 연결. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void linkToc(Course locked, Integer expectedVersion, Long materialId, String fileHash) {
        int current = locked.getTextbookVersion() == null ? 0 : locked.getTextbookVersion();
        if (expectedVersion != null && expectedVersion != current) {
            throw new ConflictException(ErrorCode.TEXTBOOK_VERSION_CHANGED);
        }
        int rows = courseMapper.writeTextbookTocLink(locked.getCourseId(), locked.getUserId(), current, materialId,
                fileHash, BookKey.of(locked));
        if (rows != 1) {
            throw new ConflictException(ErrorCode.TEXTBOOK_VERSION_CHANGED);
        }
        refService.ensureBook(locked.getUserId(), locked.getTextbookTitle(), locked.getTextbookIsbn(),
                locked.getTextbookEdition());
        locked.setTextbookVersion(current + 1);
        events.publishEvent(new TextbookChangedEvent(locked.getUserId(), locked.getCourseId(), current + 1));
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static String cut(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
