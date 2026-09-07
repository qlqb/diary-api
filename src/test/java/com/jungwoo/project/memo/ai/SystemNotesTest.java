package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionStatus;
import com.jungwoo.project.memo.ai.dto.ContextSuggestionResponse;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버가 만드는 확인 문장. 모델 호출 없이 표 다섯 행을 그대로 고정한다.
 *
 * <p>이 줄이 있어야 "반영해둘게요"라고 말해 놓고 아무것도 남지 않는 일이 구조적으로 막힌다 —
 * 실제로 생긴 것으로만 문장을 만들고, 생긴 것이 없으면 아무 말도 하지 않는다.
 */
class SystemNotesTest {

    private static ScheduleSuggestionResponse suggestion(ScheduleSuggestionKind kind, Map<String, Object> payload) {
        return ScheduleSuggestionResponse.builder()
                .suggestionId(1L).kind(kind).payload(payload).status(ScheduleSuggestionStatus.PROPOSED).build();
    }

    private static Map<String, Object> leadPayload(Integer minutes, String summary) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("routineIds", List.of(1, 2));
        payload.put("leadMinutes", minutes);
        payload.put("targetSummary", summary);
        return payload;
    }

    @Test
    void 아무것도_없으면_문장도_없다() {
        assertThat(SystemNotes.forTurn(List.of(), List.of(), 0)).isNull();
        assertThat(SystemNotes.forTurn(null, null, 0)).isNull();
    }

    @Test
    void 맥락_후보만_있으면_시간을_비우지_않는다고_말한다() {
        ContextSuggestionResponse context = ContextSuggestionResponse.builder().suggestionId(9L).build();

        assertThat(SystemNotes.forTurn(List.of(context), List.of(), 0))
                .isEqualTo("맥락으로 기록했어요. 계획 판단에 쓰이지만, 시간을 비우지는 않아요.");
    }

    @Test
    void 이동시간_후보는_대상과_분을_말한다() {
        assertThat(SystemNotes.forTurn(List.of(),
                List.of(suggestion(ScheduleSuggestionKind.ROUTINE_LEAD, leadPayload(60, "자료구조 외 4개"))), 0))
                .isEqualTo("이동시간 후보 1건을 만들었어요. 승인하면 자료구조 외 4개 앞 60분이 비워져요.");
    }

    @Test
    void 시간이_정해지지_않은_이동시간_후보는_고른_만큼이라고_말한다() {
        assertThat(SystemNotes.forTurn(List.of(),
                List.of(suggestion(ScheduleSuggestionKind.ROUTINE_LEAD, leadPayload(null, "자료구조"))), 0))
                .isEqualTo("이동시간 후보 1건을 만들었어요. 승인할 때 고른 만큼 자료구조 앞이 비워져요.");
    }

    @Test
    void 약속이나_반복_일정_후보는_건수를_말한다() {
        assertThat(SystemNotes.forTurn(List.of(), List.of(
                suggestion(ScheduleSuggestionKind.COMMITMENT, Map.of("title", "병원")),
                suggestion(ScheduleSuggestionKind.ROUTINE, Map.of("title", "알바"))), 0))
                .isEqualTo("일정 후보 2건을 만들었어요. 승인하면 그 시간이 비워져요.");
    }

    @Test
    void 대상을_못_찾았으면_그렇게_말한다() {
        assertThat(SystemNotes.forTurn(List.of(), List.of(), 1))
                .isEqualTo("어느 일정 앞인지 찾지 못했어요. 일정 이름을 알려주시면 다시 만들게요.");
    }

    /** 여럿이면 후보 문장 뒤에 못 찾은 문장이 붙고, 맥락 문장은 후보가 있으면 생략된다. */
    @Test
    void 여러_종류가_함께_생기면_후보_문장이_먼저다() {
        ContextSuggestionResponse context = ContextSuggestionResponse.builder().suggestionId(9L).build();

        assertThat(SystemNotes.forTurn(List.of(context),
                List.of(suggestion(ScheduleSuggestionKind.COMMITMENT, Map.of("title", "병원"))), 1))
                .isEqualTo("일정 후보 1건을 만들었어요. 승인하면 그 시간이 비워져요. "
                        + "어느 일정 앞인지 찾지 못했어요. 일정 이름을 알려주시면 다시 만들게요.");
    }

    @Test
    void 승인_뒤_문장은_저장된_값을_말한다() {
        assertThat(SystemNotes.forLeadApplied("수업 일정", 60)).isEqualTo("수업 일정의 이동시간을 60분으로 저장했어요.");
        assertThat(SystemNotes.forLeadApplied("쿠팡 알바", 0)).isEqualTo("쿠팡 알바의 이동시간을 없음으로 저장했어요.");
    }

    /** 문구 금지어. 실패·미완료·이행률·누락은 어느 문장에도 없다. */
    @Test
    void 금지어를_쓰지_않는다() {
        for (String text : List.of(SystemNotes.CONTEXT_ONLY, SystemNotes.SCHEDULE_TEMPLATE, SystemNotes.LEAD_TEMPLATE,
                SystemNotes.LEAD_TEMPLATE_UNDECIDED, SystemNotes.LEAD_UNRESOLVED,
                SystemNotes.LEAD_APPLIED_TEMPLATE, SystemNotes.LEAD_APPLIED_NONE_TEMPLATE)) {
            assertThat(text).doesNotContain("실패", "미완료", "이행률", "누락");
        }
    }
}
