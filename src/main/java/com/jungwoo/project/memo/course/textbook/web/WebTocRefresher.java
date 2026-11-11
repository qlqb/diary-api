package com.jungwoo.project.memo.course.textbook.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 목차 구조화 규칙이 바뀌면(WebTocStructurer.TOC_VERSION) 옛 판으로 읽은 리비전을 <b>저장된 원문으로</b> 다시 읽는다. 페이지를 다시
 * 받지 않고, 웹 검색도 하지 않는다. 폴러 틱마다 조금씩 돈다.
 *
 * <ul>
 *   <li>규칙으로만 다시 읽는다(모델 보완 없음 — 누구의 호출 한도로 부를지 정할 수 없는 서버 작업이다). 못 읽은 줄이 남으면 그 수가
 *       리비전에 남고 화면이 "목차 일부"로 보인다. 사용자가 [다시 찾기]를 누르면 조회 작업이 그 사용자 한도 안에서 모델 보완을 한다.</li>
 *   <li>옛 리비전은 지우지 않는다(옛 토픽·정리안의 근거). 해결기({@code TocResolver.effective})가 같은 원문의 새 판을 따라간다.</li>
 *   <li>다시 읽은 것이 있으면 과목별 최근 조회의 자동 정리를 다시 보게 한다 — 목차 근거(리비전)가 바뀌었으므로 새 정리안을 만든다.
 *       같은 근거의 정리안이 이미 있으면 만들지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebTocRefresher {

    static final int BATCH = 10;

    private final TextbookWebMapper webMapper;
    private final WebEvidenceStore store;
    private final TextbookLookupMapper lookupMapper;

    /** @return 다시 읽은 리비전 수 */
    public int refreshOutdated() {
        List<TextbookWebRevision> outdated = webMapper.findNeedingRestructure(BookPageParser.VERSION,
                WebTocStructurer.TOC_VERSION, BATCH);
        int done = 0;
        for (TextbookWebRevision old : outdated) {
            try {
                WebTocStructurer.Structured toc = WebTocStructurer.byRules(old.getTocRaw(), WebEvidenceStore.rawTruncated(old));
                TextbookWebRevision saved = store.saveRestructured(old, toc, scopeUser(old.getCacheScopeKey()));
                log.info("목차를 새 구조화 규칙으로 다시 읽음: revisionId={} → {}, 항목 {} → {}, 못 읽은 줄 {}",
                        old.getRevisionId(), saved == null ? null : saved.getRevisionId(), old.getTocEntryCount(),
                        toc.entries().size(), toc.unread());
                done++;
            } catch (Exception e) {
                log.warn("목차 다시 읽기 실패: revisionId={}, {}", old.getRevisionId(), e.getClass().getSimpleName());
            }
        }
        if (done > 0) {
            lookupMapper.reopenAutoTidyAfterTocRefresh();
        }
        return done;
    }

    /** USER:{id} 범위면 그 사용자, 공유면 null(공유 리비전은 누구나 읽는다). */
    static Long scopeUser(String cacheScopeKey) {
        if (cacheScopeKey == null || !cacheScopeKey.startsWith("USER:")) {
            return null;
        }
        try {
            return Long.valueOf(cacheScopeKey.substring(5));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
