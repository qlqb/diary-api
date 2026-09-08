package com.jungwoo.project.memo.ai.draft;

import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver.WorkShift;
import com.jungwoo.project.memo.ai.dto.AiTurnStructured;
import com.jungwoo.project.memo.commitment.domain.DerivedTravelRelation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.jungwoo.project.memo.ai.draft.DraftFixtures.OM;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.SHIFT_THU;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.SHIFT_TUE;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.TODAY;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.byRef;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.appliedAfter;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.existing;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.facts;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.factsWithLookupFailure;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.input;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.openWorkSchedule;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.structured;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * "이번 주 모든 근무 후에 이동 1시간" 재현. 실호출 없이 서버 판정과 후보 생성만 본다.
 *
 * <p>기준 실패는 이랬다: 근무 종료 시각이 프롬프트에 없고 AFTER 기준도 없어서, 모델은 anchor를
 * 낼 수 없었고 date/startTime 누락으로 떨어져 서버가 근무 종료 시각을 사용자에게 되물었다.
 * 사용자가 "서버에 있는 거 쓰라"고 해도 서버가 프롬프트에 싣지 않은 값을 모델이 쓸 수는 없다.
 */
class AfterWorkTravelDraftTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 8, 1, 0);
    private static final LocalDate WEEK_FROM = LocalDate.of(2026, 9, 8);
    private static final LocalDate WEEK_TO = LocalDate.of(2026, 9, 13);
    private static final String BASE = "\"decision\":\"ASK_CLARIFICATION\",\"clarifyingQuestion\":\"q?\","
            + "\"proposalItems\":[],\"missingInformation\":[],\"unavailableWindows\":[],\"contextChanges\":[],"
            + "\"scheduleSuggestions\":[]";

    // ===== 시각 계산 =====

    /** 종료 직후 60분. 23:00 근무는 다음 날 00:00까지다 — 같은 날 00:00으로 접히지 않는다. */
    @Test
    void afterAnchor_startsAtShiftEnd_andCrossesMidnight() {
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        var built = DraftProposalBuilder.build(draft, facts(), OM);

        assertThat(built).hasSize(2);
        assertThat(built.get(0).payload().path("startAt").asText()).isEqualTo("2026-09-08T23:00");
        assertThat(built.get(0).payload().path("endAt").asText()).isEqualTo("2026-09-09T00:00");
        assertThat(built.get(1).payload().path("startAt").asText()).isEqualTo("2026-09-10T22:00");
        assertThat(built.get(1).payload().path("endAt").asText()).isEqualTo("2026-09-10T23:00");
    }

    /** 23:30 + 60분은 다음 날 00:30이다. 길이를 줄이거나 23:59로 자르지 않는다. */
    @Test
    void afterAnchor_lateShift_keepsFullDurationIntoNextDay() {
        WorkShift late = new WorkShift(201L, "근무",
                LocalDateTime.of(2026, 9, 13, 18, 0), LocalDateTime.of(2026, 9, 13, 23, 30));
        DraftFacts f = facts(List.of(late), List.of(), Map.of(), WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        var built = DraftProposalBuilder.build(draft, f, OM);

        assertThat(built).hasSize(1);
        assertThat(built.get(0).payload().path("startAt").asText()).isEqualTo("2026-09-13T23:30");
        // 마지막 날 근무의 이동이 기간 밖으로 넘어가도 자르지 않는다.
        assertThat(built.get(0).payload().path("endAt").asText()).isEqualTo("2026-09-14T00:30");
    }

    /** 근무 전 이동은 그대로 근무 시작 − duration이다(회귀). */
    @Test
    void beforeAnchor_stillEndsAtShiftStart() {
        DraftState draft = openWorkSchedule(8, T0, "근무 전 이동",
                DraftSlotRegistry.ANCHOR_BEFORE_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        var built = DraftProposalBuilder.build(draft, facts(), OM);

        assertThat(built).hasSize(2);
        assertThat(built.get(0).payload().path("startAt").asText()).isEqualTo("2026-09-08T17:00");
        assertThat(built.get(0).payload().path("endAt").asText()).isEqualTo("2026-09-08T18:00");
    }

    // ===== 요청 기간 =====

    /** 요청한 주 밖의 근무에는 만들지 않는다. */
    @Test
    void requestedRange_excludesShiftsOutsideIt() {
        WorkShift nextWeek = new WorkShift(203L, "근무",
                LocalDateTime.of(2026, 9, 16, 18, 0), LocalDateTime.of(2026, 9, 16, 23, 0));
        DraftFacts f = facts(List.of(SHIFT_TUE, SHIFT_THU, nextWeek), List.of(), Map.of(),
                TODAY, TODAY.plusDays(14), DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        var built = DraftProposalBuilder.build(draft, f, OM);

        assertThat(built).hasSize(2);
        assertThat(built).noneMatch(s -> s.payload().path("startAt").asText().startsWith("2026-09-16"));
    }

    /**
     * 요청 기간이 기본 조회 기간(오늘~+14일) 밖이면 그 기간으로 다시 조회한다. 기본 기간에
     * 근무가 없다고 "근무 없음"으로 답하면 거짓말이 된다.
     */
    @Test
    void rangeBeyondDefaultWindow_requeriesInsteadOfClaimingNoShifts() {
        LocalDate farFrom = LocalDate.of(2026, 10, 5);
        LocalDate farTo = LocalDate.of(2026, 10, 11);
        WorkShift october = new WorkShift(204L, "근무",
                LocalDateTime.of(2026, 10, 6, 18, 0), LocalDateTime.of(2026, 10, 6, 23, 0));
        DraftFacts base = facts(List.of(SHIFT_TUE, SHIFT_THU), List.of(), Map.of(),
                TODAY, TODAY.plusDays(14), DraftFacts.WorkLookup.OK);
        DraftFacts far = facts(List.of(october), List.of(), Map.of(), farFrom, farTo, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, farFrom, farTo);
        boolean[] requeried = {false};
        DraftFactsSource source = (from, to) -> {
            if (farFrom.equals(from) && farTo.equals(to)) {
                requeried[0] = true;
                return far;
            }
            return base;
        };

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(
                input(List.of(draft), structured("{" + BASE + ",\"routing\":{\"targets\":[9],\"create\":[]},"
                        + "\"draftOps\":[],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}"),
                        "네", false, base, source));

        assertThat(requeried[0]).isTrue();
        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(DraftProposalBuilder.build(draft, out.factsFor(draft, base), OM))
                .singleElement()
                .satisfies(s -> assertThat(s.payload().path("startAt").asText()).isEqualTo("2026-10-06T23:00"));
    }

    // ===== 막혀 있던 draft 복구 =====

    /**
     * 재현의 핵심. anchor 없이 date/startTime 누락으로 막혀 있던 draft에 AFTER 기준이 들어오면
     * 그 draft가 이어서 진행된다 — 새 draft를 만들지 않고, 낡은 누락은 사라진다.
     */
    @Test
    void stuckDraft_getsAfterAnchor_andStaleMissingClears() {
        DraftState stuck = openWorkSchedule(9, T0, "근무 후 이동시간", null, WEEK_FROM, WEEK_TO);
        stuck.setMissingRequired(List.of(DraftSlotRegistry.DATE, DraftSlotRegistry.START_TIME));
        stuck.setAskCount(4);
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[9],\"create\":[]},"
                + "\"draftOps\":[{\"draftId\":9,\"op\":\"SET\",\"field\":\"anchor\","
                + "\"value\":\"AFTER_EACH_WORK_SHIFT\",\"source\":\"USER\"}],"
                + "\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(
                input(List.of(stuck), s, "근무시간 서버에 있는 거 사용해", false, facts()));

        assertThat(out.drafts()).hasSize(1);
        DraftState after = byRef(out, "9");
        assertThat(after.getMissingRequired()).isEmpty();
        assertThat(after.readiness()).isEqualTo(DraftReadiness.READY);
        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(out.proposeDrafts()).containsExactly(after);
    }

    /** 정보가 충분한 한 발화면 OPEN draft가 없어도 되묻지 않고 바로 후보를 만든다. */
    @Test
    void singleUtterance_withNoOpenDrafts_proposesWithoutAsking() {
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[],\"create\":["
                + "{\"draftType\":\"CREATE_SCHEDULE\",\"label\":\"근무 후 이동시간\",\"sameGroupAs\":null}]},"
                + "\"draftOps\":["
                + "{\"draftId\":\"new-0\",\"op\":\"SET\",\"field\":\"durationMinutes\",\"value\":60,\"source\":\"USER\"},"
                + "{\"draftId\":\"new-0\",\"op\":\"SET\",\"field\":\"anchor\",\"value\":\"AFTER_EACH_WORK_SHIFT\",\"source\":\"USER\"},"
                + "{\"draftId\":\"new-0\",\"op\":\"SET\",\"field\":\"dateRange\","
                + "\"value\":{\"from\":\"2026-09-08\",\"to\":\"2026-09-13\"},\"source\":\"USER\"}],"
                + "\"actionHint\":\"PROPOSE\",\"userTriggered\":true}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(
                input(List.of(), s, "이번 주 모든 근무 후에 이동시간 1시간 만들어줘. 일회성으로.", false, facts()));

        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(out.askDraft()).isNull();
        assertThat(byRef(out, "new-0").getMissingRequired()).isEmpty();
    }

    // ===== 데이터 품질과 조회 상태 =====

    /** 종료 시각이 없는 근무가 섞여 있으면 그 근무를 지정해 묻고, 일부만 만들고 끝내지 않는다. */
    @Test
    void oneShiftMissingEnd_holdsWholeDraft_andNamesThatShift() {
        WorkShift broken = new WorkShift(205L, "근무", LocalDateTime.of(2026, 9, 11, 18, 0), null);
        DraftFacts f = facts(List.of(SHIFT_TUE), List.of(broken), Map.of(),
                WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(
                input(List.of(draft), structured("{" + BASE + ",\"routing\":{\"targets\":[9],\"create\":[]},"
                        + "\"draftOps\":[],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}"),
                        "네", false, f));

        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.ASK);
        assertThat(byRef(out, "9").getMissingRequired())
                .containsExactly(DraftSlotRegistry.MISSING_WORK_SHIFT_END);
        assertThat(out.serverReply()).contains("9월 11일").contains("일정 화면");
        assertThat(out.proposeDrafts()).isEmpty();
    }

    /** 근무 앞 이동은 종료 시각이 없어도 계산된다 — 시작 시각만 쓴다. */
    @Test
    void beforeAnchor_ignoresMissingEnd() {
        WorkShift broken = new WorkShift(205L, "근무", LocalDateTime.of(2026, 9, 11, 18, 0), null);
        DraftFacts f = facts(List.of(SHIFT_TUE), List.of(broken), Map.of(),
                WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(8, T0, "근무 전 이동",
                DraftSlotRegistry.ANCHOR_BEFORE_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        assertThat(DraftSlotRegistry.missingRequired(draft.getType(), draft.getFields(),
                DraftTurnResolver.anchorFacts(draft, f))).isEmpty();
    }

    /** 조회 실패와 "근무 0건"은 다른 문구다. 실패를 근무 없음으로 위장하지 않는다. */
    @Test
    void lookupFailure_andEmptyResult_giveDifferentReplies() {
        DraftState a = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);
        DraftState b = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[9],\"create\":[]},"
                + "\"draftOps\":[],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome failed = DraftTurnResolver.resolve(
                input(List.of(a), s, "네", false, factsWithLookupFailure()));
        DraftTurnResolver.Outcome empty = DraftTurnResolver.resolve(
                input(List.of(b), s, "네", false,
                        facts(List.of(), List.of(), Map.of(), WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK)));

        assertThat(byRef(failed, "9").getMissingRequired())
                .containsExactly(DraftSlotRegistry.MISSING_WORK_LOOKUP);
        assertThat(failed.serverReply()).contains("불러오지 못").contains("없다는 뜻은 아니");
        assertThat(failed.quickReplies()).contains("다시 시도");

        assertThat(byRef(empty, "9").getMissingRequired())
                .containsExactly(DraftSlotRegistry.MISSING_WORK_SHIFTS);
        assertThat(empty.serverReply()).contains("9월 8일~9월 13일").contains("등록된 근무가 없어요");
    }

    /** 조회 실패 draft가 있어도 준비된 다른 draft는 그대로 만들어진다. */
    @Test
    void lookupFailure_doesNotBlockUnrelatedReadyDraft() {
        DraftState broken = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);
        DraftState plain = openWorkSchedule(10, T0.plusMinutes(1), "치과", null, null, null);
        plain.getFields().put(DraftSlotRegistry.DATE,
                new DraftField(com.fasterxml.jackson.databind.node.TextNode.valueOf("2026-09-09"),
                        FieldSource.USER, null, false));
        plain.getFields().put(DraftSlotRegistry.START_TIME,
                new DraftField(com.fasterxml.jackson.databind.node.TextNode.valueOf("10:00"),
                        FieldSource.USER, null, false));
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[9,10],\"create\":[]},"
                + "\"draftOps\":[],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(
                input(List.of(broken, plain), s, "네", false, factsWithLookupFailure()));

        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(out.proposeDrafts()).containsExactly(plain);
        // 실패한 draft는 삭제되지도, 성공한 것처럼 처리되지도 않는다.
        assertThat(byRef(out, "9").getStatus()).isEqualTo(DraftStatus.OPEN);
        assertThat(byRef(out, "9").getMissingRequired())
                .containsExactly(DraftSlotRegistry.MISSING_WORK_LOOKUP);
    }

    // ===== 중복 =====

    /** 요청과 <b>구간까지 같은</b> 이동이 이미 있는 근무만 건너뛴다. 왜 뺐는지도 말한다. */
    @Test
    void identicalExistingTravel_isSkipped_andReported() {
        DraftFacts f = facts(List.of(SHIFT_TUE, SHIFT_THU), List.of(),
                Map.ofEntries(appliedAfter(SHIFT_TUE, 60, 900L)),
                WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        var plan = DraftProposalBuilder.plan(draft, f, OM);

        assertThat(plan.create()).hasSize(1);
        assertThat(plan.create().get(0).payload().path("startAt").asText()).isEqualTo("2026-09-10T22:00");
        assertThat(plan.unchanged()).hasSize(1);
        assertThat(plan.conflicts()).isEmpty();
    }

    /**
     * 기존 30분이 새 60분 요청을 조용히 막지 않는다. 이건 "이미 있음"이 아니라 "고쳐야 함"이다 —
     * 기준 커밋에서는 원본 id + 방향만 봐서 30분짜리가 60분 요청을 삼켰다.
     */
    @Test
    void existingTravelWithDifferentLength_isConflict_notAlreadyCovered() {
        DraftFacts f = facts(List.of(SHIFT_TUE, SHIFT_THU), List.of(),
                Map.ofEntries(appliedAfter(SHIFT_TUE, 30, 900L)),
                WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        var plan = DraftProposalBuilder.plan(draft, f, OM);

        assertThat(plan.unchanged()).isEmpty();
        assertThat(plan.conflicts()).hasSize(1);
        assertThat(plan.conflicts().get(0).existing().minutes()).isEqualTo(30);
        assertThat(plan.conflicts().get(0).wantedMinutes()).isEqualTo(60);
        // 없는 근무에는 그대로 새로 만든다 — 혼합 요청에서 신규가 막히면 안 된다.
        assertThat(plan.create()).hasSize(1);
        assertThat(plan.create().get(0).payload().path("startAt").asText()).isEqualTo("2026-09-10T22:00");
    }

    /** 혼합 요청: 신규는 만들고, 길이가 다른 것은 수정 필요로 구분해 특정한다. */
    @Test
    void mixedRequest_createsNew_andNamesTheOneNeedingChange() {
        DraftFacts f = facts(List.of(SHIFT_TUE, SHIFT_THU), List.of(),
                Map.ofEntries(appliedAfter(SHIFT_TUE, 30, 900L)),
                WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[9],\"create\":[]},"
                + "\"draftOps\":[],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(draft), s, "네", false, f));

        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(out.serverReply()).contains("후보 1건");
        // 전부 완료로 표시하지 않는다. 어느 근무를 어떻게 고쳐야 하는지 말한다.
        assertThat(out.serverReply()).contains("9월 8일").contains("30분").contains("60분")
                .contains("일정 화면");
    }

    /** 그 기간 근무에 요청과 같은 이동이 다 있으면 "이대로 만들까요"가 아니라 그 사실을 말한다. */
    @Test
    void allShiftsAlreadyIdentical_explainsInsteadOfOfferingEmptyCreate() {
        DraftFacts f = facts(List.of(SHIFT_TUE, SHIFT_THU), List.of(),
                Map.ofEntries(appliedAfter(SHIFT_TUE, 60, 900L), appliedAfter(SHIFT_THU, 60, 901L)),
                WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[9],\"create\":[]},"
                + "\"draftOps\":[],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(draft), s, "다시 만들어줘", false, f));

        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.ASK);
        assertThat(out.proposeDrafts()).isEmpty();
        assertThat(out.serverReply()).contains("이미 다 있어요");
    }

    /** 미적용 후보도 기존 이동으로 본다 — 같은 요청을 다시 말해도 카드가 두 장 되지 않는다. */
    @Test
    void pendingSuggestion_countsAsExisting_soCardsDoNotPileUp() {
        DraftFacts f = facts(List.of(SHIFT_TUE), List.of(),
                Map.ofEntries(existing(SHIFT_TUE, DerivedTravelRelation.AFTER_WORK,
                        SHIFT_TUE.endAt(), SHIFT_TUE.endAt().plusMinutes(60),
                        ExistingTravel.Source.PROPOSED, 700L)),
                WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        var plan = DraftProposalBuilder.plan(draft, f, OM);

        assertThat(plan.create()).isEmpty();
        assertThat(plan.unchanged()).singleElement()
                .satisfies(e -> assertThat(e.source()).isEqualTo(ExistingTravel.Source.PROPOSED));
    }

    /** 미적용 후보와 구간이 다르면 조용히 건너뛰지 않고 수정 필요로 특정한다. */
    @Test
    void pendingSuggestionWithDifferentInterval_isConflict() {
        DraftFacts f = facts(List.of(SHIFT_TUE), List.of(),
                Map.ofEntries(existing(SHIFT_TUE, DerivedTravelRelation.AFTER_WORK,
                        SHIFT_TUE.endAt(), SHIFT_TUE.endAt().plusMinutes(90),
                        ExistingTravel.Source.PROPOSED, 700L)),
                WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        var plan = DraftProposalBuilder.plan(draft, f, OM);

        assertThat(plan.create()).isEmpty();
        assertThat(plan.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.existing().source()).isEqualTo(ExistingTravel.Source.PROPOSED));
    }

    /**
     * 종료 시각을 못 쓰는 근무에도 <b>앞</b> 이동은 만들어진다. 준비 여부 판정은 허용하는데
     * 생성만 정상 목록을 돌면, READY인데 후보가 0건이라 이유 없는 되물음이 나간다.
     */
    @Test
    void beforeAnchor_createsForIncompleteShiftsToo() {
        WorkShift broken = new WorkShift(205L, "근무", LocalDateTime.of(2026, 9, 11, 18, 0), null);
        DraftFacts f = facts(List.of(SHIFT_TUE), List.of(broken), Map.of(),
                WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(8, T0, "근무 전 이동",
                DraftSlotRegistry.ANCHOR_BEFORE_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        var built = DraftProposalBuilder.build(draft, f, OM);

        assertThat(built).hasSize(2);
        assertThat(built).extracting(x -> x.payload().path("startAt").asText())
                .containsExactly("2026-09-08T17:00", "2026-09-11T17:00");
    }

    /** 종료가 이상한 근무만 있어도 앞 이동은 만들 수 있다 — "근무 없음"이 아니다. */
    @Test
    void beforeAnchor_worksWhenOnlyIncompleteShiftsExist() {
        WorkShift broken = new WorkShift(205L, "근무", LocalDateTime.of(2026, 9, 11, 18, 0), null);
        DraftFacts f = facts(List.of(), List.of(broken), Map.of(),
                WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        DraftState draft = openWorkSchedule(8, T0, "근무 전 이동",
                DraftSlotRegistry.ANCHOR_BEFORE_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[8],\"create\":[]},"
                + "\"draftOps\":[],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(draft), s, "네", false, f));

        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.PROPOSE);
        assertThat(DraftProposalBuilder.build(draft, f, OM)).hasSize(1);
    }

    /** 후보 payload는 원본 근무와 앞/뒤, 기준 시각을 들고 다닌다 — 적용 경로가 이것을 읽는다. */
    @Test
    void payload_carriesOriginReference() {
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);

        var built = DraftProposalBuilder.build(draft, facts(), OM);

        var derived = built.get(0).payload().path(DraftProposalBuilder.DERIVED_FROM_KEY);
        assertThat(derived.path(DraftProposalBuilder.DERIVED_COMMITMENT_ID).asLong())
                .isEqualTo(SHIFT_TUE.commitmentId());
        assertThat(derived.path(DraftProposalBuilder.DERIVED_RELATION).asText()).isEqualTo("AFTER_WORK");
        assertThat(derived.path(DraftProposalBuilder.DERIVED_ANCHOR_AT).asText()).isEqualTo("2026-09-08T23:00");
    }

    // ===== 반복 질문 =====

    /** 같은 누락으로 상한만큼 물었으면 승격하지 않고, 멈춘 이유와 빠져나갈 길을 말한다. */
    @Test
    void sameMissingRepeated_neverPromotes_andExplainsStall() {
        DraftState stuck = openWorkSchedule(9, T0, "근무 후 이동시간", null, WEEK_FROM, WEEK_TO);
        stuck.setMissingRequired(List.of(DraftSlotRegistry.DATE, DraftSlotRegistry.START_TIME));
        stuck.setAskCount(3);
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[9],\"create\":[]},"
                + "\"draftOps\":[],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(stuck), s, "네", false, facts()));

        assertThat(out.action()).isEqualTo(DraftTurnResolver.Action.ASK);
        assertThat(out.proposeDrafts()).isEmpty();
        assertThat(out.serverReply()).contains("아직 받지 못해서").contains("취소");
        assertThat(out.quickReplies()).contains("취소");
        // 나중에 값을 주면 이어갈 수 있어야 한다.
        assertThat(byRef(out, "9").getStatus()).isEqualTo(DraftStatus.OPEN);
    }

    /** 누락이 바뀌었으면 진전이 있었던 것이다. ask_count가 높아도 평소 질문을 쓴다. */
    @Test
    void missingChanged_isNotTreatedAsStalled() {
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);
        draft.setMissingRequired(List.of(DraftSlotRegistry.DATE, DraftSlotRegistry.START_TIME));
        draft.setAskCount(5);
        DraftFacts f = facts(List.of(), List.of(), Map.of(), WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[9],\"create\":[]},"
                + "\"draftOps\":[],\"actionHint\":\"PROPOSE\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(input(List.of(draft), s, "네", false, f));

        assertThat(out.serverReply()).doesNotContain("아직 받지 못해서");
        assertThat(byRef(out, "9").getMissingRequired())
                .containsExactly(DraftSlotRegistry.MISSING_WORK_SHIFTS);
    }

    /** 서버가 누락을 들고 있으면 모델의 되물음("종료 직후가 맞나요?")을 그대로 내보내지 않는다. */
    @Test
    void modelQuestion_isReplacedWhenServerHasMissing() {
        DraftState draft = openWorkSchedule(9, T0, "근무 후 이동시간",
                DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT, WEEK_FROM, WEEK_TO);
        DraftFacts f = facts(List.of(), List.of(), Map.of(), WEEK_FROM, WEEK_TO, DraftFacts.WorkLookup.OK);
        AiTurnStructured s = structured("{" + BASE + ",\"routing\":{\"targets\":[9],\"create\":[]},"
                + "\"draftOps\":[],\"actionHint\":\"ASK\",\"userTriggered\":false}");

        DraftTurnResolver.Outcome out = DraftTurnResolver.resolve(
                input(List.of(draft), s, "근무 종료 직후가 맞나요?", false, f));

        assertThat(out.serverReply()).isNotNull().doesNotContain("종료 직후가 맞나요");
    }
}
