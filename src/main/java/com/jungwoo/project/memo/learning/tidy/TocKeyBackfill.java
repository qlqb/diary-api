package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import com.jungwoo.project.memo.course.textbook.TocResolver;
import com.jungwoo.project.memo.course.textbook.web.TextbookWebMapper;
import com.jungwoo.project.memo.course.textbook.web.TextbookWebRevision;
import com.jungwoo.project.memo.course.textbook.web.WebEvidenceStore;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 목차 항목 열쇠가 생기기 전(2026-10-06 전)에 목차에서 만든 토픽에 열쇠를 한 번 채운다. 서버가 시작할 때 돈다(멱등 — 상태가 빈 행만).
 *
 * <p><b>그 토픽이 만들어질 때의 목차</b>로 정한다. 지금 목차·지금 제목으로 추측하지 않는다.
 * <ul>
 *   <li>웹 목차 토픽(만든 리비전이 있음): 표시 순번이 있으면 그 리비전의 그 순번 항목의 원문 줄. 순번이 없으면(순번 칸이 생기기 전 토픽)
 *       그 리비전 항목 중 "번호 제목"이 지금 토픽 제목과 정확히 같은 것이 <b>하나</b>일 때만 — 사용자가 제목을 고쳤으면 정하지 않는다.</li>
 *   <li>업로드 목차 토픽: 만들 때의 파일 해시·추출 판을 확정할 근거가 없다 → 정하지 않는다.</li>
 *   <li>정하지 못하면 MISSING_SOURCE(정리에서 "짝을 확정 못 함"으로 보이고, 그 토픽이 걸린 곳의 목차 추가를 보류한다).</li>
 * </ul>
 * 열쇠 칸과 상태만 쓴다. 제목·부모·순서·출처 칸은 바꾸지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TocKeyBackfill implements ApplicationRunner {

    static final int BATCH = 500;

    private final CourseTopicMapper topicMapper;
    private final TextbookWebMapper webMapper;
    private final TocResolver tocResolver;

    @Value("${toc.key-backfill.enabled:true}")
    private boolean enabled = true;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            int[] counts = runOnce();
            if (counts[0] + counts[1] > 0) {
                log.info("목차 열쇠 일회 변환: 정함 {}개, 확정 못 함 {}개", counts[0], counts[1]);
            }
        } catch (Exception e) {
            // 열쇠가 없으면 정리가 그 토픽을 "목차 토픽 아님"으로 볼 뿐이다(지금 동작). 서버 시작을 막지 않는다.
            log.warn("목차 열쇠 일회 변환 실패: {}", e.getClass().getSimpleName(), e);
        }
    }

    /** @return {정함, 확정 못 함} */
    public int[] runOnce() {
        int set = 0;
        int missing = 0;
        while (true) {
            List<CourseTopic> targets = topicMapper.findTocKeyBackfillTargets(BATCH);
            if (targets.isEmpty()) {
                break;
            }
            for (CourseTopic topic : targets) {
                Key key = keyOf(topic);
                if (key == null) {
                    topicMapper.backfillTocKey(topic.getTopicId(), null, null, null, "MISSING_SOURCE");
                    missing++;
                } else {
                    topicMapper.backfillTocKey(topic.getTopicId(), "WEB", key.hash(), key.line(), "SET");
                    set++;
                }
            }
            if (targets.size() < BATCH) {
                break;
            }
        }
        return new int[]{set, missing};
    }

    record Key(String hash, int line) {
    }

    private Key keyOf(CourseTopic topic) {
        if (topic.getSourceWebRevisionId() == null) {
            return null; // 업로드 목차 — 만들 때의 원문을 확정할 수 없다
        }
        TextbookWebRevision revision = webMapper.findRevision(topic.getSourceWebRevisionId(), topic.getUserId());
        if (revision == null || revision.getTocRaw() == null) {
            return null;
        }
        String hash = revision.getTocRawHash() != null ? revision.getTocRawHash()
                : WebEvidenceStore.rawHash(revision.getTocRaw());
        List<TextbookExtractor.TocEntry> entries = tocResolver.webEntries(revision.getTocJson());
        return keyIn(entries, topic, hash);
    }

    /** 만든 리비전의 항목들에서 이 토픽의 항목을 찾는다. 확정할 수 없으면 null. */
    static Key keyIn(List<TextbookExtractor.TocEntry> entries, CourseTopic topic, String hash) {
        if (hash == null || entries == null || entries.isEmpty()) {
            return null;
        }
        TextbookExtractor.TocEntry found = null;
        Integer seq = topic.getSourceTocSeq();
        if (seq != null) {
            if (seq < 1 || seq > entries.size()) {
                return null;
            }
            found = entries.get(seq - 1); // 옛 순번 = 그 리비전 구조화 목록의 위치
        } else {
            // 정확히 같은 제목만(공백만 정리). 구두점·기호를 지우면 "C++"를 "C"로 고친 제목도 같다고 보게 된다 — 열쇠는 바꾸지 않으므로
            // 잘못 정하면 고칠 수 없다.
            String want = exact(topic.getTitle());
            for (TextbookExtractor.TocEntry e : entries) {
                if (exact(TocSkeleton.titleOf(e)).equals(want)) {
                    if (found != null) {
                        return null; // 같은 제목이 둘 — 정하지 않는다
                    }
                    found = e;
                }
            }
        }
        return found == null || found.unit() < 1 ? null : new Key(hash, found.unit());
    }

    private static String exact(String title) {
        return title == null ? "" : title.strip().replaceAll("\\s+", " ");
    }
}
