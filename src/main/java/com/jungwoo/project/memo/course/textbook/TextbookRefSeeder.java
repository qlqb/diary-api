package com.jungwoo.project.memo.course.textbook;

import com.jungwoo.project.memo.learning.events.LearningEventMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 교재 식별자를 한 번 채운다(계획 §11.6). 서버가 시작할 때 돈다 — {@code learning_event_meta.textbook_refs_seeded}가 있으면 건너뛴다.
 *
 * <ol>
 *   <li>교재 칸이 있는 과목마다 그 책의 ISBN 키·제목 키를 같은 ref로. 사용자 안에서 두 ref가 같은 책으로 드러나면(한 과목은 ISBN,
 *       다른 과목은 제목만) 하나로 합친다 — 단, 이벤트가 이미 가리킨 ref는 합치지 않는다.</li>
 *   <li>토픽(source_textbook_key)·과목(textbook_toc_book_key)에 남은 키마다 ref를 보장한다.</li>
 * </ol>
 * 과목 하나 = 트랜잭션 하나. 실패해도 서버 시작을 막지 않는다(다음 시작에 다시 — 멱등).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextbookRefSeeder implements ApplicationRunner {

    private final TextbookRefMapper mapper;
    private final TextbookRefService refService;
    private final LearningEventMetaMapper metaMapper;
    private final TransactionTemplate tx;

    @Value("${learning.events.textbook-ref-seed.enabled:true}")
    private boolean enabled = true;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            int[] counts = runOnce();
            if (counts[0] + counts[1] > 0) {
                log.info("교재 식별자 시드: 과목 교재 {}개, 남은 키 {}개, 합침 {}개", counts[0], counts[1], counts[2]);
            }
        } catch (Exception e) {
            log.warn("교재 식별자 시드 실패: {}", e.getClass().getSimpleName(), e);
        }
    }

    /** @return {과목 교재, 남은 키, 합침} */
    public int[] runOnce() {
        if (metaMapper.find(LearningEventMetaMapper.TEXTBOOK_REFS_SEEDED) != null) {
            return new int[]{0, 0, 0};
        }
        int books = 0;
        int keys = 0;
        int[] merged = {0};
        for (TextbookRefMapper.CourseBook b : mapper.findCourseBooks()) {
            tx.executeWithoutResult(s -> merged[0] += seedBook(b));
            books++;
        }
        for (TextbookRefMapper.UserKey k : mapper.findStoredBookKeys()) {
            tx.executeWithoutResult(s -> refService.refFor(k.userId(), k.bookKey()));
            keys++;
        }
        metaMapper.insertIgnore(LearningEventMetaMapper.TEXTBOOK_REFS_SEEDED, LocalDateTime.now().toString());
        return new int[]{books, keys, merged[0]};
    }

    /** 같은 책의 키들이 서로 다른 ref에 있으면, 이벤트가 가리키지 않은 쪽을 합친다. */
    private int seedBook(TextbookRefMapper.CourseBook b) {
        int merged = 0;
        Set<Long> refs = new LinkedHashSet<>();
        for (String key : TextbookRefService.keysOf(b.title(), b.isbn(), b.edition())) {
            Long r = refService.find(b.userId(), key);
            if (r != null) {
                refs.add(r);
            }
        }
        if (refs.size() > 1) {
            Long keep = refs.iterator().next();
            for (Long r : refs) {
                if (r.equals(keep) || mapper.countEventsUsingRef(b.userId(), "t:" + r + ":") > 0) {
                    continue;
                }
                mapper.moveAliases(b.userId(), r, keep);
                mapper.deleteRef(b.userId(), r);
                merged++;
            }
        }
        refService.ensureBook(b.userId(), b.title(), b.isbn(), b.edition());
        return merged;
    }
}
