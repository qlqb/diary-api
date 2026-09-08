package com.jungwoo.project.memo.ai.draft;

import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver.WorkShift;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.jungwoo.project.memo.ai.draft.DraftFixtures.TODAY;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.facts;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.factsWithLookupFailure;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.openWorkSchedule;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * [관련 실제 데이터] 블록. 기준 실패의 출발점이 여기였다 — 근무를 시작 시각만 실어서, 모델은
 * "근무 후 이동"을 만들라는 요청에 쓸 종료 시각을 프롬프트 어디에서도 볼 수 없었다.
 */
class DraftPromptBuilderFactsTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 8, 1, 0);

    @Test
    void workLines_carryIdAndBothTimes_andLookupState() {
        String block = DraftPromptBuilder.renderFacts(facts());

        assertThat(block).contains("근무 조회 성공 · 대상 기간 2026-09-08~2026-09-22");
        assertThat(block).contains("근무 #101: 2026-09-08 18:00 → 2026-09-08 23:00");
        assertThat(block).contains("근무 #102: 2026-09-10 17:00 → 2026-09-10 22:00");
    }

    @Test
    void lookupFailure_isNotWrittenAsNoShifts() {
        String block = DraftPromptBuilder.renderFacts(factsWithLookupFailure());

        assertThat(block).contains("근무 조회 실패");
        assertThat(block).doesNotContain("등록된 근무 없음");
    }

    @Test
    void emptyResult_saysSoWithTheRange() {
        String block = DraftPromptBuilder.renderFacts(facts(List.of(), List.of(), Set.of(),
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 13), DraftFacts.WorkLookup.OK));

        assertThat(block).contains("근무 조회 성공 · 대상 기간 2026-09-08~2026-09-13 · 등록된 근무 없음");
    }

    @Test
    void missingEnd_isMarked_notOmitted() {
        DraftFacts f = facts(List.of(),
                List.of(new WorkShift(205L, "근무", LocalDateTime.of(2026, 9, 11, 18, 0), null)),
                Set.of(), TODAY, TODAY.plusDays(14), DraftFacts.WorkLookup.OK);

        assertThat(DraftPromptBuilder.renderFacts(f))
                .contains("근무 #205: 2026-09-11 18:00 → 종료 시각 없음");
    }

    /** 표시 상한을 넘으면 생략을 명시한다 — 서버 계산은 전체가 대상이라는 것까지. */
    @Test
    void manyShifts_declareOmission() {
        List<WorkShift> many = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            many.add(new WorkShift(300L + i, "근무",
                    LocalDateTime.of(2026, 9, 8, 18, 0).plusDays(i),
                    LocalDateTime.of(2026, 9, 8, 23, 0).plusDays(i)));
        }
        DraftFacts f = facts(many, List.of(), Set.of(), TODAY, TODAY.plusDays(14), DraftFacts.WorkLookup.OK);

        String block = DraftPromptBuilder.renderFacts(f);

        assertThat(block).contains("근무 총 14건 중 10건만 표시").contains("서버 계산은 전체 대상");
    }

    /**
     * draft가 많아도 근무 줄이 통째로 사라지거나 중간에서 끊기지 않는다. 예전에는 두 블록을
     * 이어 붙인 뒤 전체를 잘라, 사실 블록이 뒤에 있어서 먼저 없어졌다.
     */
    @Test
    void manyDrafts_neverEatTheFactsBlock() {
        List<DraftState> drafts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            drafts.add(openWorkSchedule(100 + i, T0, "아주 긴 라벨을 가진 근무 후 이동 블록 요청 " + i,
                    DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT,
                    LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 13)));
        }

        String block = DraftPromptBuilder.renderOpenDrafts(drafts, facts());

        assertThat(block).hasSizeLessThanOrEqualTo(DraftPromptBuilder.MAX_CHARS);
        assertThat(block).contains(DraftPromptBuilder.FACTS_HEADER);
        assertThat(block).contains("근무 #101: 2026-09-08 18:00 → 2026-09-08 23:00");
        // 잘린 자리가 있어도 줄 단위다 — "→ 2026-09-08 2"처럼 끊긴 조각을 남기지 않는다.
        for (String line : block.split("\n")) {
            if (line.startsWith("근무 #")) {
                assertThat(line).matches("근무 #\\d+: .+ → (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}|종료 시각 없음.*)");
            }
        }
    }

    /** 모델 계약에 AFTER 기준이 실제로 적혀 있어야 모델이 낼 수 있다. */
    @Test
    void systemRules_declareBothWorkAnchors() {
        assertThat(DraftPromptBuilder.SYSTEM_RULES)
                .contains(DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT)
                .contains(DraftSlotRegistry.ANCHOR_BEFORE_EACH_WORK_SHIFT)
                .contains("근무 후");
    }
}
