package com.jungwoo.project.memo.course.textbook.web;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 웹 근거(페이지·리비전). 읽기는 모두 범위를 건다 — 공유(SHARED)이거나 그 사용자의 것(USER:{id})만.
 */
@Mapper
public interface TextbookWebMapper {

    int insertPageIgnore(TextbookWebPage page);

    TextbookWebPage findPage(@Param("cacheScopeKey") String cacheScopeKey, @Param("urlHash") String urlHash);

    int updatePageFetch(@Param("pageId") Long pageId, @Param("latestRevisionId") Long latestRevisionId,
                        @Param("status") String status, @Param("fetchedAt") LocalDateTime fetchedAt);

    int insertRevisionIgnore(TextbookWebRevision revision);

    TextbookWebRevision findRevisionByContent(@Param("pageId") Long pageId, @Param("contentHash") String contentHash,
                                              @Param("parserVersion") int parserVersion);

    /** 범위 안의 리비전. 다른 사용자의 USER 페이지 리비전은 null이다. */
    TextbookWebRevision findRevision(@Param("revisionId") Long revisionId, @Param("userId") Long userId);

    List<TextbookWebRevision> findRevisions(@Param("revisionIds") List<Long> revisionIds, @Param("userId") Long userId);

    /** 같은 ISBN을 가진 범위 안 페이지들의 최신 리비전. 재검색 없이 판 근거를 다시 쓸 때. */
    List<TextbookWebRevision> findLatestByIsbn(@Param("isbn13") String isbn13, @Param("userId") Long userId,
                                               @Param("since") LocalDateTime since,
                                               @Param("parserVersion") int parserVersion);
}
