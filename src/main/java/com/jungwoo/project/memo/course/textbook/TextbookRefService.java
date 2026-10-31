package com.jungwoo.project.memo.course.textbook;

import com.jungwoo.project.memo.learning.events.LearningEventMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 바뀌지 않는 교재 식별자(설계 20번 §5.1, 계획 §2.3·§11.6).
 *
 * <p>BookKey는 같은 책이어도 바뀐다(제목 키 → ISBN을 채우면 ISBN 키). 이벤트는 키가 아니라 {@code book_ref_id}를 가리키고, 그 책의
 * 키들은 ref의 별칭이다.
 * <ul>
 *   <li>교재 칸을 쓸 때마다 그 책의 ISBN 키·제목 키를 같은 ref로 보장한다({@link #ensureBook}).</li>
 *   <li>새 키가 이미 <b>다른</b> ref의 별칭이면 합치지 않는다 — 이벤트가 이미 가리킨 ref를 바꾸지 않기 위해서. 경고를 남기고 충돌
 *       카운터를 올린다(조용히 무시하지 않는다).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextbookRefService {

    private final TextbookRefMapper mapper;
    private final LearningEventMetaMapper metaMapper;

    /** 키의 ref. 없으면 만든다. 키가 비면 null. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long refFor(long userId, String bookKey) {
        if (bookKey == null || bookKey.isBlank()) {
            return null;
        }
        Long found = find(userId, bookKey);
        if (found != null) {
            return found;
        }
        TextbookRefMapper.RefRow row = new TextbookRefMapper.RefRow(userId);
        mapper.insertRef(row);
        if (mapper.insertAliasIgnore(userId, hash(bookKey), bookKey, row.getBookRefId()) == 1) {
            return row.getBookRefId();
        }
        // 동시에 다른 요청이 같은 키를 먼저 넣었다 — 그쪽 ref를 쓰고 방금 만든 빈 ref는 지운다.
        // 일반 읽기는 이 트랜잭션의 옛 스냅샷이라 방금 커밋된 별칭을 못 볼 수 있다 — 잠금 읽기로 본다.
        mapper.deleteRef(userId, row.getBookRefId());
        Long winner = findLocked(userId, bookKey);
        if (winner == null) {
            throw new IllegalStateException("교재 별칭을 만들지도 찾지도 못했다");
        }
        return winner;
    }

    /**
     * 같은 책의 식별을 바꿨다(예: 제목만 알던 책에 ISBN·판을 채움). 이전 키들의 ref를 이어 새 키들을 별칭으로 더한다 — 이전 이벤트와 새
     * 이벤트가 같은 교재 식별자를 가리키게. 이전 키에 ref가 없으면 {@link #ensureBook}과 같다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long continueBook(long userId, List<String> previousKeys, String title, String isbn, String edition) {
        Long ref = null;
        for (String key : previousKeys) {
            if (key != null && (ref = find(userId, key)) != null) {
                break;
            }
        }
        if (ref == null) {
            return ensureBook(userId, title, isbn, edition);
        }
        for (String key : keysOf(title, isbn, edition)) {
            attach(userId, key, ref);
        }
        return ref;
    }

    /**
     * 이 책(제목·ISBN·판)의 키들을 같은 ref로 보장한다. 어느 키도 ref가 없으면 하나 만든다.
     *
     * @return 그 책의 ref(키가 하나도 없으면 null)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long ensureBook(long userId, String title, String isbn, String edition) {
        List<String> keys = keysOf(title, isbn, edition);
        if (keys.isEmpty()) {
            return null;
        }
        Long ref = null;
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            Long r = find(userId, key);
            if (r == null) {
                missing.add(key);
            } else if (ref == null) {
                ref = r;
            } else if (!ref.equals(r)) {
                conflict(userId, key);
            }
        }
        if (ref == null) {
            // 다른 트랜잭션이 막 만들었으면 refFor가 잠금 읽기로 그 ref를 돌려준다 — 다시 찾지 않고 그 값을 쓴다
            // (이 트랜잭션의 일반 읽기는 옛 스냅샷이라 다시 찾으면 계속 없다).
            ref = refFor(userId, keys.get(0));
            missing.remove(keys.get(0));
        }
        for (String key : missing) {
            attach(userId, key, ref);
        }
        return ref;
    }

    /** key를 ref의 별칭으로. 이미 다른 ref의 별칭이면 합치지 않고 충돌로 센다. */
    private void attach(long userId, String key, long ref) {
        Long existing = find(userId, key);
        if (existing != null) {
            if (!existing.equals(ref)) {
                conflict(userId, key);
            }
            return;
        }
        if (mapper.insertAliasIgnore(userId, hash(key), key, ref) == 0) {
            Long other = findLocked(userId, key);
            if (other != null && !other.equals(ref)) {
                conflict(userId, key);
            }
        }
    }

    private Long findLocked(long userId, String bookKey) {
        TextbookRefMapper.AliasRow row = mapper.findAliasLocked(userId, hash(bookKey));
        if (row == null) {
            return null;
        }
        if (!bookKey.equals(row.bookKey())) {
            throw new IllegalStateException("교재 키 해시 충돌");
        }
        return row.bookRefId();
    }

    /** 한 책의 키들: 지금 쓰는 키(ISBN 있으면 ISBN 키) + 제목 키(제목+판). */
    public static List<String> keysOf(String title, String isbn, String edition) {
        Set<String> keys = new LinkedHashSet<>();
        String main = BookKey.of(title, isbn, edition);
        if (main != null) {
            keys.add(main);
        }
        String byTitle = BookKey.of(title, null, edition);
        if (byTitle != null) {
            keys.add(byTitle);
        }
        return new ArrayList<>(keys);
    }

    Long find(long userId, String bookKey) {
        TextbookRefMapper.AliasRow row = mapper.findAlias(userId, hash(bookKey));
        if (row == null) {
            return null;
        }
        if (!bookKey.equals(row.bookKey())) {
            throw new IllegalStateException("교재 키 해시 충돌");
        }
        return row.bookRefId();
    }

    private void conflict(long userId, String key) {
        log.warn("교재 별칭 충돌: userId={}, 같은 책의 키가 이미 다른 교재 식별자에 있다(합치지 않음), keyKind={}", userId,
                key.startsWith("isbn:") ? "ISBN" : "TITLE");
        metaMapper.increment(LearningEventMetaMapper.TEXTBOOK_REF_CONFLICTS);
    }

    static String hash(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
