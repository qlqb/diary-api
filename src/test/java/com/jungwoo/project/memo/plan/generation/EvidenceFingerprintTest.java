package com.jungwoo.project.memo.plan.generation;

import com.jungwoo.project.memo.plan.selection.PlanRequestContext;
import com.jungwoo.project.memo.scheduling.domain.BusySource;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 근거 지문은 시간이 흐른 것과 실제 일정이 바뀐 것을 구분한다(2026-09-17 후속 §7). 남는 시간 구간은 "지금부터"로 잘려
 * 1분마다 달라지므로 지문에 넣지 않고, 그 구간을 만든 일정(시각이 박힌 항목·수업·약속)을 본다.
 */
class EvidenceFingerprintTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 16);
    private static final LocalDate END = LocalDate.of(2026, 9, 22);

    private static BusyWindow busy(BusySource source, long id, int day, int fromHour, int toHour) {
        return new BusyWindow(LocalDateTime.of(2026, 9, day, fromHour, 0), LocalDateTime.of(2026, 9, day, toHour, 0), "일정",
                source, id);
    }

    private static EvidenceFingerprint of(List<BusyWindow> busy, LocalDateTime now) {
        return EvidenceFingerprint.of(List.of(), busy, now, START, END, List.of(1L), null, List.of(), List.of());
    }

    private static PlanRequestContext.EvidenceSnapshot snapshot(EvidenceFingerprint f, LocalDateTime capturedAt) {
        return new PlanRequestContext.EvidenceSnapshot(f.fingerprint(), f.availabilityHash(), f.materialsHash(),
                f.assignmentsHash(), f.progressHash(), capturedAt, List.of(), List.of());
    }

    @Test
    void 같은_일정에서_1분이_지나도_지문은_같고_변경_안내가_없다() {
        List<BusyWindow> busy = List.of(busy(BusySource.ROUTINE_OCCURRENCE, 9, 16, 14, 15), busy(BusySource.COMMITMENT, 3, 18, 19, 21));
        EvidenceFingerprint first = of(busy, LocalDateTime.of(2026, 9, 16, 13, 0));
        EvidenceFingerprint later = of(busy, LocalDateTime.of(2026, 9, 16, 13, 1));

        assertThat(later.sameAs(snapshot(first, LocalDateTime.of(2026, 9, 16, 13, 0)))).isTrue();
        assertThat(later.changesFrom(snapshot(first, LocalDateTime.of(2026, 9, 16, 13, 0)), null, START, END, List.of(1L), null,
                List.of(), LocalDate.of(2026, 9, 16))).isEmpty();
    }

    @Test
    void 오늘_가용_구간이_13시에서_14시로_줄면_일정이_생긴_것이라_감지한다() {
        EvidenceFingerprint before = of(List.of(busy(BusySource.ROUTINE_OCCURRENCE, 9, 16, 14, 15)),
                LocalDateTime.of(2026, 9, 16, 12, 0));
        // 13~14시에 약속이 생겼다 → 남는 시간 13~23시가 14~23시로 줄었다.
        EvidenceFingerprint after = of(List.of(busy(BusySource.ROUTINE_OCCURRENCE, 9, 16, 14, 15),
                busy(BusySource.COMMITMENT, 5, 16, 13, 14)), LocalDateTime.of(2026, 9, 16, 12, 30));

        assertThat(after.sameAs(snapshot(before, LocalDateTime.of(2026, 9, 16, 12, 0)))).isFalse();
        assertThat(after.changesFrom(snapshot(before, LocalDateTime.of(2026, 9, 16, 12, 0)), null, START, END, List.of(1L),
                null, List.of(), LocalDate.of(2026, 9, 16)))
                .containsExactly("일정(수업·약속·시각이 정해진 항목)이 달라져 남는 시간이 바뀌었다");
    }

    @Test
    void 종료_시각_변경_구간_분할_삭제_수업_시각_변경을_모두_감지한다() {
        List<BusyWindow> base = List.of(busy(BusySource.ROUTINE_OCCURRENCE, 9, 16, 14, 15),
                busy(BusySource.COMMITMENT, 3, 18, 19, 21));
        EvidenceFingerprint origin = of(base, LocalDateTime.of(2026, 9, 16, 9, 0));

        EvidenceFingerprint endChanged = of(List.of(busy(BusySource.ROUTINE_OCCURRENCE, 9, 16, 14, 15),
                busy(BusySource.COMMITMENT, 3, 18, 19, 22)), LocalDateTime.of(2026, 9, 16, 9, 0));
        EvidenceFingerprint split = of(List.of(busy(BusySource.ROUTINE_OCCURRENCE, 9, 16, 14, 15),
                busy(BusySource.COMMITMENT, 3, 18, 19, 21), busy(BusySource.EXECUTION_ITEM, 70, 17, 20, 21)),
                LocalDateTime.of(2026, 9, 16, 9, 0));
        EvidenceFingerprint removed = of(List.of(busy(BusySource.ROUTINE_OCCURRENCE, 9, 16, 14, 15)),
                LocalDateTime.of(2026, 9, 16, 9, 0));
        EvidenceFingerprint classMoved = of(List.of(busy(BusySource.ROUTINE_OCCURRENCE, 9, 16, 15, 16),
                busy(BusySource.COMMITMENT, 3, 18, 19, 21)), LocalDateTime.of(2026, 9, 16, 9, 0));

        for (EvidenceFingerprint changed : List.of(endChanged, split, removed, classMoved)) {
            assertThat(changed.availabilityHash()).isNotEqualTo(origin.availabilityHash());
            assertThat(changed.sameAs(snapshot(origin, LocalDateTime.of(2026, 9, 16, 9, 0)))).isFalse();
        }
    }

    @Test
    void 자정이_지나면_일정이_같아도_지문이_달라지고_날짜가_바뀌었다고_적는다() {
        List<BusyWindow> busy = List.of(busy(BusySource.ROUTINE_OCCURRENCE, 9, 16, 14, 15));
        EvidenceFingerprint yesterday = of(busy, LocalDateTime.of(2026, 9, 16, 23, 50));
        EvidenceFingerprint today = of(busy, LocalDateTime.of(2026, 9, 17, 0, 10));

        assertThat(today.availabilityHash()).isEqualTo(yesterday.availabilityHash());
        assertThat(today.sameAs(snapshot(yesterday, LocalDateTime.of(2026, 9, 16, 23, 50)))).isFalse();
        assertThat(today.changesFrom(snapshot(yesterday, LocalDateTime.of(2026, 9, 16, 23, 50)), null, START, END, List.of(1L),
                null, List.of(), LocalDate.of(2026, 9, 17)))
                .containsExactly("날짜가 2026-09-16에서 2026-09-17로 바뀌어 오늘 이후의 일정·마감을 다시 확인했다");
    }
}
