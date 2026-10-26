package com.jungwoo.project.memo.commitment;

import com.jungwoo.project.memo.commitment.domain.Commitment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 아직 적용하지 않은 약속 변경(가정 상태). 조회 결과에 겹쳐 쓰며 DB는 건드리지 않는다.
 *
 * <p>commitmentId → 바뀐 약속. 값의 isDeleted가 true면 취소, 새 약속은 음수 id다.
 */
public final class CommitmentOverlay {

    public static final CommitmentOverlay NONE = new CommitmentOverlay(Map.of());

    private final Map<Long, Commitment> changes;

    private CommitmentOverlay(Map<Long, Commitment> changes) {
        this.changes = changes;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** {@link CommitmentService#findOverlapping}와 같은 [from 00:00, to+1 00:00) 겹침 기준으로 겹쳐 놓는다. */
    public List<Commitment> apply(List<Commitment> overlapping, LocalDate from, LocalDate to) {
        if (changes.isEmpty()) {
            return overlapping;
        }
        LocalDateTime windowStart = from.atStartOfDay();
        LocalDateTime windowEnd = to.plusDays(1).atStartOfDay();
        List<Commitment> out = new ArrayList<>();
        for (Commitment c : overlapping) {
            if (!changes.containsKey(c.getCommitmentId())) {
                out.add(c);
            }
        }
        for (Commitment c : changes.values()) {
            if (!Boolean.TRUE.equals(c.getIsDeleted())
                    && c.getStartAt().isBefore(windowEnd) && c.getEndAt().isAfter(windowStart)) {
                out.add(c);
            }
        }
        out.sort((a, b) -> a.getStartAt().compareTo(b.getStartAt()));
        return out;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<Long, Commitment> changes = new LinkedHashMap<>();

        public Builder put(Commitment commitment) {
            changes.put(commitment.getCommitmentId(), commitment);
            return this;
        }

        public CommitmentOverlay build() {
            return changes.isEmpty() ? NONE : new CommitmentOverlay(Collections.unmodifiableMap(new LinkedHashMap<>(changes)));
        }
    }
}
