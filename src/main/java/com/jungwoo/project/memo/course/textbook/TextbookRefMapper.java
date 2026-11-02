package com.jungwoo.project.memo.course.textbook;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 교재 식별자(textbook_refs)와 그 책의 BookKey 별칭. */
@Mapper
public interface TextbookRefMapper {

    /** 별칭 하나. 없으면 null. 해시로 찾고 호출자가 전체 키를 비교한다. */
    AliasRow findAlias(@Param("userId") long userId, @Param("bookKeyHash") String bookKeyHash);

    /** 잠금 읽기(공유) — 다른 트랜잭션이 막 커밋한 별칭도 본다. */
    AliasRow findAliasLocked(@Param("userId") long userId, @Param("bookKeyHash") String bookKeyHash);

    int insertRef(RefRow row);

    int insertAliasIgnore(@Param("userId") long userId, @Param("bookKeyHash") String bookKeyHash,
                          @Param("bookKey") String bookKey, @Param("bookRefId") long bookRefId);

    /** 시드에서만: 이벤트가 아직 가리키지 않은 ref의 별칭을 다른 ref로 옮긴다. */
    int moveAliases(@Param("userId") long userId, @Param("fromRefId") long fromRefId, @Param("toRefId") long toRefId);

    int deleteRef(@Param("userId") long userId, @Param("bookRefId") long bookRefId);

    /** 이 ref를 가리키는 학습 이벤트가 있나. */
    int countEventsUsingRef(@Param("userId") long userId, @Param("refPrefix") String refPrefix);

    /** 시드: 교재 칸이 있는 과목들. */
    List<CourseBook> findCourseBooks();

    /** 시드: 토픽·과목에 남은 교재 키(사용자별 서로 다른 값). */
    List<UserKey> findStoredBookKeys();

    record AliasRow(Long bookRefId, String bookKey) {
    }

    record CourseBook(Long userId, Long courseId, String title, String isbn, String edition) {
    }

    record UserKey(Long userId, String bookKey) {
    }

    /** insertRef용(생성 키를 받는다). */
    class RefRow {
        private Long bookRefId;
        private Long userId;

        public RefRow(Long userId) {
            this.userId = userId;
        }

        public Long getBookRefId() {
            return bookRefId;
        }

        public void setBookRefId(Long bookRefId) {
            this.bookRefId = bookRefId;
        }

        public Long getUserId() {
            return userId;
        }
    }
}
