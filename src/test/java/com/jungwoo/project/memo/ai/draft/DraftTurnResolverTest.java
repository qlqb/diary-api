package com.jungwoo.project.memo.ai.draft;

import com.jungwoo.project.memo.ai.dto.AiTurnStructured;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.jungwoo.project.memo.ai.draft.DraftFixtures.CONVERSATION_ID;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.SEMESTER_END;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.TODAY;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.byRef;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.facts;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.factsWithoutSemesterEnd;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.input;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.openPeriodPlan;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.openRoutine;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.openSchedule;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.structured;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-8 fixture(실호출 없음). 각 케이스는 "모델 응답 JSON → 서버 결과"를 단언한다. 서버 판정은 순수 함수라
 * DB·Spring 없이 그대로 돈다. 저장·승격은 DraftPromotionServiceTest, 잠금은 AiTurnLifecycleServiceTest.
 */
class DraftTurnResolverTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 8, 1, 0);
    private static final String BASE = "\"decision\":\"ASK_CLARIFICATION\",\"clarifyingQuestion\":\"q?\","
            + "\"proposalItems\":[],\"missingInformation\":[],\"unavailableWindows\":[],\"contextChanges\":[],"
            + "\"scheduleSuggestions\":[]";

    /** #1 첫 문장: create 2개 → 같은 그룹, ROUTINE anchor는 서버 규칙으로 확인 필요, SCHEDULE만 PROPOSE + 질문 한 줄. */
    @Test
    void firstUtterance_createsTwoDrafts_proposesScheduleOnly_andAsksAboutRoutine() {
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[],\"create\":["
                + "{\"draftType\":\"CREATE_ROUTINE\",\"label\":\"수업 전 이동 루틴\",\"sameGroupAs\":null},"
                + "{\"draftType\":\"CREATE_SCHEDULE\",\"label\":\"근무 전 이동 블록\",\"sameGroupAs\":\"new-0\"}]},"
                + "\"draftOps\":["
                + "{\"draftId\":\"new-0\",\"op\":\"SET\",\"field\":\"durationMinutes\",\"value\":60,\"source\":\"USER\"},"
                // 모델은 확인 불필요로 냈지만 발화에 "첫"이 없으므로 서버가 true로 덮는다.
                + "{\"draftId\":\"new-0\",\"op\":\"SET\",\"field\":\"anchor\",\"value\":\"FIRST_CLASS_OF_DAY\",\"source\":\"INFERRED\",\"confirmationRequired\":false},"
                + "{\"draftId\":\"new-1\",\"op\":\"SET\",\"field\":\"durationMinutes\",\"value\":60,\"source\":\"USER\"},"
                + "{\"draftId\":\"new-1\",\"op\":\"SET\",\"field\":\"anchor\",\"value\":\"BEFORE_EACH_WORK_SHIFT\",\"source\":\"INFERRED\",\"confirmationRequired\":false}"
                + "],\"actionHint\":\"ASK\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(), s, "수업 전마다요?", false, facts()));

        assertThat(out.drafts()).hasSize(2);
        DraftState routine = byRef(out, "new-0");
        DraftState schedule = byRef(out, "new-1");
        assertThat(schedule.getSameGroupAs()).isEqualTo("new-0");
        assertThat(routine.getFields().get(DraftSlotRegistry.ANCHOR).confirmationRequired()).isTrue();
        assertThat(schedule.getFields().get(DraftSlotRegistry.ANCHOR).confirmationRequired()).isFalse();
        assertThat(routine.readiness()).isEqualTo(DraftReadiness.NEEDS_CONFIRM);
        assertThat(schedule.readiness()).isEqualTo(DraftReadiness.READY);
        // 서버가 채운 값
        assertThat(routine.getFields().get(DraftSlotRegistry.START_DATE).source()).isEqualTo(FieldSource.SYSTEM);
        assertThat(routine.getFields().get(DraftSlotRegistry.END_DATE).asLocalDate()).isEqualTo(SEMESTER_END);
        assertThat(routine.getFields().get(DraftSlotRegistry.END_DATE).source()).isEqualTo(FieldSource.DB);
        assertThat(schedule.getFields().get(DraftSlotRegistry.DATE_RANGE).source()).isEqualTo(FieldSource.DEFAULT);

        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(out.proposeDrafts()).containsExactly(schedule);
        assertThat(routine.getStatus()).isEqualTo(DraftStatus.OPEN);
        // mixed 지원: 서버 문구 끝에 ROUTINE 확인 질문, ROUTINE ask_count=1
        assertThat(out.askDraft()).isSameAs(routine);
        assertThat(routine.getAskCount()).isEqualTo(1);
        assertThat(schedule.getAskCount()).isZero();
        assertThat(out.serverReply()).contains("근무 전 이동 블록 후보 2건").endsWith("첫 수업 전만요?");
        assertThat(out.quickReplies()).isEqualTo(DraftTurnResolver.ANCHOR_CONFIRM_QUICK_REPLIES);
    }

    /** #2 "매일 수업 첫시간에만": anchor USER → 확인 해제 → 종강일 있으면 READY→PROPOSE, 없으면 NEEDS_INPUT. */
    @Test
    void userConfirmsAnchor_readyWithSemesterEnd_needsInputWithout() {
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[31],\"create\":[]},"
                + "\"draftOps\":[{\"draftId\":31,\"op\":\"SET\",\"field\":\"anchor\",\"value\":\"FIRST_CLASS_OF_DAY\",\"source\":\"USER\"}],"
                + "\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome ready = DraftTurnResolver.resolve(
                input(List.of(openRoutine(31, T0, false)), s, "만들게요.", true, facts()));
        assertThat(ready.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(byRef(ready, "31").readiness()).isEqualTo(DraftReadiness.READY);
        assertThat(ready.serverReply()).contains("수업 전 이동 루틴 후보 4건");

        DraftState withoutEnd = openRoutine(31, T0, false);
        withoutEnd.getFields().remove(DraftSlotRegistry.END_DATE);
        DraftTurnResolver.Outcome needsInput = DraftTurnResolver.resolve(
                input(List.of(withoutEnd), s, "만들게요.", true, factsWithoutSemesterEnd()));
        assertThat(needsInput.action()).isEqualTo(DraftTurnResolver.Action.ASK);
        assertThat(byRef(needsInput, "31").readiness()).isEqualTo(DraftReadiness.NEEDS_INPUT);
        assertThat(byRef(needsInput, "31").getMissingRequired()).containsExactly(DraftSlotRegistry.END_DATE);
        // 모델 reply가 질문이 아니라 서버 문구로 대체
        assertThat(needsInput.serverReply()).contains("언제까지");
    }

    /** #3 READY인데 모델은 ASK + 질문문 → 서버가 PROPOSE로 뒤집고 모델 reply 폐기. */
    @Test
    void readyDraft_overridesModelAskHint_andDiscardsModelReply() {
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[31],\"create\":[]},"
                + "\"draftOps\":[{\"draftId\":31,\"op\":\"SET\",\"field\":\"anchor\",\"value\":\"FIRST_CLASS_OF_DAY\",\"source\":\"USER\"}],"
                + "\"actionHint\":\"ASK\",\"userTriggered\":false}");
        String modelQuestion = "종료일은 언제까지로 할까요?";

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(
                input(List.of(openRoutine(31, T0, false)), s, modelQuestion, true, facts()));

        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(out.serverReply()).isNotNull().doesNotContain(modelQuestion);
        assertThat(out.askDraft()).isNull();
    }

    /** #4 "아니다 수업 시작 시각에 맞춰 자동으로": CLEAR startTime + SET anchor 순서 적용. */
    @Test
    void clearThenSetAnchor_appliesInOrder() {
        DraftState fixedTime = openRoutine(31, T0, false);
        fixedTime.getFields().remove(DraftSlotRegistry.ANCHOR);
        fixedTime.getFields().put(DraftSlotRegistry.DAYS_OF_WEEK,
                new DraftField(DraftFixtures.OM.createArrayNode().add("TUESDAY").add("WEDNESDAY"), FieldSource.USER, null, false));
        fixedTime.getFields().put(DraftSlotRegistry.START_TIME,
                new DraftField(com.fasterxml.jackson.databind.node.TextNode.valueOf("09:00"), FieldSource.USER, null, false));
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[31],\"create\":[]},\"draftOps\":["
                + "{\"draftId\":31,\"op\":\"CLEAR\",\"field\":\"startTime\"},"
                + "{\"draftId\":31,\"op\":\"SET\",\"field\":\"anchor\",\"value\":\"FIRST_CLASS_OF_DAY\",\"source\":\"INFERRED\",\"confirmationRequired\":true}"
                + "],\"actionHint\":\"ASK\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(fixedTime), s, "첫 수업 전만요?", false, facts()));

        DraftState d = byRef(out, "31");
        assertThat(d.getFields()).doesNotContainKey(DraftSlotRegistry.START_TIME);
        assertThat(d.getFields().get(DraftSlotRegistry.ANCHOR).asText()).isEqualTo(DraftSlotRegistry.ANCHOR_FIRST_CLASS_OF_DAY);
        assertThat(d.getMissingRequired()).isEmpty();
        assertThat(d.readiness()).isEqualTo(DraftReadiness.NEEDS_CONFIRM);
        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.ASK);
        // 모델 reply가 질문이라 그대로 쓴다(serverReply 없음)
        assertThat(out.serverReply()).isNull();
    }

    /** #5 (가장 중요) PERIOD_PLAN draft에서 "그냥 루틴만 만들어줘" → RETYPE → intensity가 사라진다(모델이 CLEAR를 안 냈어도). */
    @Test
    void retypeToRoutine_dropsIntensity_evenWithoutClear() {
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[40],\"create\":[]},\"draftOps\":["
                + "{\"draftId\":40,\"op\":\"RETYPE\",\"draftType\":\"CREATE_ROUTINE\"},"
                + "{\"draftId\":40,\"op\":\"SET\",\"field\":\"durationMinutes\",\"value\":60,\"source\":\"USER\"},"
                + "{\"draftId\":40,\"op\":\"SET\",\"field\":\"anchor\",\"value\":\"FIRST_CLASS_OF_DAY\",\"source\":\"INFERRED\",\"confirmationRequired\":true}"
                + "],\"actionHint\":\"ASK\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(
                input(List.of(openPeriodPlan(40, T0)), s, "첫 수업 전만요?", false, facts()));

        DraftState d = byRef(out, "40");
        assertThat(d.getType()).isEqualTo(DraftType.CREATE_ROUTINE);
        assertThat(d.getFields()).doesNotContainKey(DraftSlotRegistry.INTENSITY);
        // 새 타입에도 있는 startDate/endDate(USER)는 살아남는다.
        assertThat(d.getFields().get(DraftSlotRegistry.START_DATE).source()).isEqualTo(FieldSource.USER);
        assertThat(d.getMissingRequired()).isEmpty();
        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.ASK);
    }

    /** #6 userTriggered + NEEDS_CONFIRM 1개 → PROPOSE, assumedFields=anchor, fieldNotes=DB/SYSTEM. 정상 READY는 assumed 비어 있음. */
    @Test
    void userTriggered_proposesUnconfirmed_withAssumedFields() {
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[31],\"create\":[]},\"draftOps\":[],"
                + "\"actionHint\":\"PROPOSE\",\"userTriggered\":true}");
        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(
                input(List.of(openRoutine(31, T0, false)), s, "만들게요.", false, facts()));

        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        DraftState d = byRef(out, "31");
        assertThat(out.proposeDrafts()).containsExactly(d);
        assertThat(out.serverReply()).contains("확인 없이 넣은 값");
        var built = DraftProposalBuilder.build(d, facts(), DraftFixtures.OM);
        assertThat(built).hasSize(4);
        var payload = built.get(0).payload();
        assertThat(payload.get(DraftProposalBuilder.ASSUMED_FIELDS_KEY)).hasSize(1);
        assertThat(payload.get(DraftProposalBuilder.ASSUMED_FIELDS_KEY).get(0).get("field").asText()).isEqualTo("anchor");
        assertThat(payload.get(DraftProposalBuilder.FIELD_NOTES_KEY).findValuesAsText("field"))
                .containsExactlyInAnyOrder("title", "startDate", "endDate", "classDaysOnly");
        assertThat(payload.get(DraftProposalBuilder.FIELD_NOTES_KEY).findValuesAsText("reason"))
                .contains(DraftSlotRegistry.REASON_ACTIVE_SEMESTER_END, DraftSlotRegistry.REASON_CURRENT_DATE);

        // 정상 READY 경로(#2): assumedFields 빈 배열, fieldNotes에 endDate/startDate
        DraftState confirmed = openRoutine(31, T0, true);
        DraftTurnResolver.Outcome readyOut = DraftTurnResolver.resolve(input(List.of(confirmed), structured(
                "{" + BASE + ",\"routing\":{\"targets\":[31],\"create\":[]},\"draftOps\":[],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}"),
                "만들게요.", true, facts()));
        assertThat(readyOut.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        var readyPayload = DraftProposalBuilder.build(byRef(readyOut, "31"), facts(), DraftFixtures.OM).get(0).payload();
        assertThat(readyPayload.get(DraftProposalBuilder.ASSUMED_FIELDS_KEY)).isEmpty();
        assertThat(readyPayload.get(DraftProposalBuilder.FIELD_NOTES_KEY).findValuesAsText("field"))
                .contains("startDate", "endDate");
    }

    /** #7 userTriggered + NEEDS_INPUT → PROPOSE 안 함, 서버 문구로 누락만 되물음. */
    @Test
    void userTriggered_needsInput_asksOnlyMissing() {
        DraftState noDuration = openRoutine(31, T0, true);
        noDuration.getFields().remove(DraftSlotRegistry.DURATION_MINUTES);
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[31],\"create\":[]},\"draftOps\":[],"
                + "\"actionHint\":\"PROPOSE\",\"userTriggered\":true}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(noDuration), s, "만들게요.", true, facts()));

        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.ASK);
        assertThat(out.proposeDrafts()).isEmpty();
        assertThat(out.serverReply()).contains("길이만 알려주시면 이어서 만들게요");
        assertThat(byRef(out, "31").getAskCount()).isEqualTo(1);
    }

    /** #8 NEEDS_CONFIRM ask_count=3 → READY 승격. NEEDS_INPUT은 승격 없음. 두 NEEDS_CONFIRM이면 #31만 질문받고 #32는 0. */
    @Test
    void askLimit_promotesOnlyNeedsConfirm_andCountsOnlyAskedDraft() {
        AiTurnStructured both = structured("{" + BASE + ",\"routing\":{\"targets\":[31,32],\"create\":[]},\"draftOps\":[],"
                + "\"actionHint\":\"ASK\",\"userTriggered\":false}");
        DraftState a = openRoutine(31, T0, false);
        DraftState b = openRoutine(32, T0.plusMinutes(1), false);
        b.setLabel("두 번째 루틴");
        for (int turn = 0; turn < 3; turn++) {
            DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(a, b), both, "첫 수업 전만요?", false, facts()));
            assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.ASK);
            assertThat(out.askDraft()).isSameAs(a);
        }
        assertThat(a.getAskCount()).isEqualTo(3);
        assertThat(b.getAskCount()).isZero();

        // 4번째: #31은 ask_count 3으로 READY 승격 → PROPOSE(가정 표시), #32가 이제 질문 대상
        DraftTurnResolver.Outcome promoted = DraftTurnResolver.resolve(input(List.of(a, b), both, "첫 수업 전만요?", false, facts()));
        assertThat(promoted.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(promoted.proposeDrafts()).containsExactly(a);
        assertThat(promoted.askDraft()).isSameAs(b);
        assertThat(b.getAskCount()).isEqualTo(1);
        assertThat(promoted.notes()).anyMatch(n -> n.contains("ask_count 상한"));

        // NEEDS_INPUT은 ask_count와 무관하게 승격하지 않는다
        DraftState noDuration = openRoutine(33, T0, true);
        noDuration.getFields().remove(DraftSlotRegistry.DURATION_MINUTES);
        noDuration.setAskCount(3);
        AiTurnStructured one = structured("{" + BASE + ",\"routing\":{\"targets\":[33],\"create\":[]},\"draftOps\":[],"
                + "\"actionHint\":\"ASK\",\"userTriggered\":false}");
        DraftTurnResolver.Outcome stillAsk = DraftTurnResolver.resolve(input(List.of(noDuration), one, "몇 분?", true, facts()));
        assertThat(stillAsk.action()).isEqualTo(DraftTurnResolver.Action.ASK);
        assertThat(stillAsk.proposeDrafts()).isEmpty();
    }

    /** #9 "둘 다 30분으로": targets=[31,32], 두 draft 모두 durationMinutes=30. */
    @Test
    void bothTargets_updateBothDrafts() {
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[31,32],\"create\":[]},\"draftOps\":["
                + "{\"draftId\":31,\"op\":\"SET\",\"field\":\"durationMinutes\",\"value\":30,\"source\":\"USER\"},"
                + "{\"draftId\":32,\"op\":\"SET\",\"field\":\"durationMinutes\",\"value\":30,\"source\":\"USER\"}"
                + "],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(
                input(List.of(openRoutine(31, T0, true), openSchedule(32, T0)), s, "네.", true, facts()));

        assertThat(byRef(out, "31").getFields().get(DraftSlotRegistry.DURATION_MINUTES).asInt()).isEqualTo(30);
        assertThat(byRef(out, "32").getFields().get(DraftSlotRegistry.DURATION_MINUTES).asInt()).isEqualTo(30);
        assertThat(out.effectiveTargets()).containsExactlyInAnyOrder("31", "32");
        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(out.proposeDrafts()).hasSize(2);
    }

    /** §7: targets를 비우고 draftOps에만 draftId → draftOps 참조를 targets로 간주. 없는 draftId는 그 op만 버린다. */
    @Test
    void emptyTargets_useDraftOpsRefs_andDropUnknownIds() {
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[],\"create\":[]},\"draftOps\":["
                + "{\"draftId\":31,\"op\":\"SET\",\"field\":\"durationMinutes\",\"value\":45,\"source\":\"USER\"},"
                + "{\"draftId\":999,\"op\":\"SET\",\"field\":\"durationMinutes\",\"value\":45,\"source\":\"USER\"},"
                // 모델이 낼 수 없는 출처 → 버림
                + "{\"draftId\":31,\"op\":\"SET\",\"field\":\"endDate\",\"value\":\"2026-11-30\",\"source\":\"DB\"}"
                + "],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(openRoutine(31, T0, true)), s, "네.", true, facts()));

        assertThat(out.effectiveTargets()).containsExactly("31");
        assertThat(byRef(out, "31").getFields().get(DraftSlotRegistry.DURATION_MINUTES).asInt()).isEqualTo(45);
        assertThat(byRef(out, "31").getFields().get(DraftSlotRegistry.END_DATE).asLocalDate()).isEqualTo(SEMESTER_END);
        assertThat(out.notes()).anyMatch(n -> n.contains("999")).anyMatch(n -> n.contains("DB"));
    }

    /** 일정 요청과 무관한 턴: targets도 create도 없음 → CHAT, 저장할 draft 없음. */
    @Test
    void noRoutingSignals_isChat() {
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[],\"create\":[]},\"draftOps\":[],"
                + "\"actionHint\":\"CHAT\",\"userTriggered\":false}");
        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(openRoutine(31, T0, false)), s, "안녕!", false, facts()));
        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.CHAT);
        assertThat(out.touchesDrafts()).isFalse();
    }

    /** CANCEL은 즉시 CANCELLED이고 그 draft는 readiness 검사에서 빠진다. */
    @Test
    void cancel_marksCancelled() {
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[32],\"create\":[]},\"draftOps\":["
                + "{\"draftId\":32,\"op\":\"CANCEL\"}],\"actionHint\":\"CHAT\",\"userTriggered\":false}");
        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(openSchedule(32, T0)), s, "알겠어요.", false, facts()));
        assertThat(byRef(out, "32").getStatus()).isEqualTo(DraftStatus.CANCELLED);
        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.CHAT);
        assertThat(out.touchesDrafts()).isTrue();
    }

    /** READY 기간 계획 draft → PROPOSE(OFFER 경로). conversationId는 fixture 그대로. */
    @Test
    void periodPlanReady_isProposedAsOffer() {
        DraftState plan = openPeriodPlan(40, T0);
        plan.getFields().put(DraftSlotRegistry.INTENSITY, new DraftField(
                com.fasterxml.jackson.databind.node.TextNode.valueOf("NORMAL"), FieldSource.USER, null, false));
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[40],\"create\":[]},\"draftOps\":[],"
                + "\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");
        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(plan), s, "네.", false, facts()));
        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(out.periodPlanProposal()).isSameAs(byRef(out, "40"));
        assertThat(out.serverReply()).contains(DraftTurnResolver.PERIOD_PLAN_OFFER_LINE);
        assertThat(plan.getConversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(plan.getFields().get(DraftSlotRegistry.START_DATE).asLocalDate()).isEqualTo(TODAY);
    }
}
