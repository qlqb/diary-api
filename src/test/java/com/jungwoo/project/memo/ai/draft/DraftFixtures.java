package com.jungwoo.project.memo.ai.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver.WorkShift;
import com.jungwoo.project.memo.ai.dto.AiTurnStructured;
import com.jungwoo.project.memo.commitment.domain.DerivedTravelRelation;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B-8 fixture 공용. 로그(2026-09-08)와 같은 모양: 오늘 2026-09-08(화), 첫 수업 화 14:00 / 수·목·금 10:00,
 * 종강 12/11, 근무 09-08 18:00 · 09-10 17:00.
 */
final class DraftFixtures {

    static final ObjectMapper OM = new ObjectMapper().findAndRegisterModules();
    static final Long USER_ID = 1L;
    static final Long CONVERSATION_ID = 10L;
    static final LocalDate TODAY = LocalDate.of(2026, 9, 8);
    static final LocalDate SEMESTER_END = LocalDate.of(2026, 12, 11);

    private DraftFixtures() {
    }

    /** 재현 로그와 같은 근무 두 건. id는 합성값이다(운영 데이터를 fixture에 넣지 않는다). */
    static final WorkShift SHIFT_TUE = new WorkShift(101L, "근무",
            LocalDateTime.of(2026, 9, 8, 18, 0), LocalDateTime.of(2026, 9, 8, 23, 0));
    static final WorkShift SHIFT_THU = new WorkShift(102L, "근무",
            LocalDateTime.of(2026, 9, 10, 17, 0), LocalDateTime.of(2026, 9, 10, 22, 0));

    static DraftFacts facts() {
        return facts(List.of(SHIFT_TUE, SHIFT_THU), List.of(), Set.of(), TODAY, TODAY.plusDays(14),
                DraftFacts.WorkLookup.OK);
    }

    static DraftFacts facts(List<WorkShift> usable, List<WorkShift> incomplete, Set<String> covered,
                            LocalDate from, LocalDate to, DraftFacts.WorkLookup lookup) {
        return new DraftFacts(TODAY,
                Map.of(DayOfWeek.TUESDAY, LocalTime.of(14, 0), DayOfWeek.WEDNESDAY, LocalTime.of(10, 0),
                        DayOfWeek.THURSDAY, LocalTime.of(10, 0), DayOfWeek.FRIDAY, LocalTime.of(10, 0)),
                Optional.of(SEMESTER_END),
                lookup, usable, incomplete, covered, from, to);
    }

    /** 근무 조회 자체가 실패한 턴. "근무 없음"과 구분돼야 한다. */
    static DraftFacts factsWithLookupFailure() {
        return facts(List.of(), List.of(), Set.of(), TODAY, TODAY.plusDays(14), DraftFacts.WorkLookup.FAILED);
    }

    /** 종강일을 모르는 프로젝트. endDate는 missing으로 남아야 한다. */
    static DraftFacts factsWithoutSemesterEnd() {
        DraftFacts f = facts();
        return new DraftFacts(f.today(), f.firstClassByDay(), Optional.empty(), f.workLookup(),
                f.workShifts(), f.incompleteShifts(), f.coveredKeys(), f.workRangeFrom(), f.workRangeTo());
    }

    static String coveredKey(WorkShift shift, DerivedTravelRelation relation) {
        return shift.commitmentId() + ":" + relation.name();
    }

    static AiTurnStructured structured(String json) {
        try {
            return OM.readValue(json, AiTurnStructured.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** 저장돼 있던 OPEN ROUTINE draft(#31 "수업 전 이동 루틴"). anchor는 INFERRED·확인 필요, 나머지는 채워져 있다. */
    static DraftState openRoutine(long id, LocalDateTime createdAt, boolean anchorConfirmed) {
        DraftState d = new DraftState();
        d.setDraftId(id);
        d.setUserId(USER_ID);
        d.setConversationId(CONVERSATION_ID);
        d.setDraftGroupId(31L);
        d.retype(DraftType.CREATE_ROUTINE);
        d.setLabel("수업 전 이동 루틴");
        d.setCreatedAt(createdAt);
        d.getFields().put(DraftSlotRegistry.TITLE, new DraftField(TextNode.valueOf("이동시간"), FieldSource.DEFAULT,
                DraftSlotRegistry.REASON_DRAFT_LABEL, false));
        d.getFields().put(DraftSlotRegistry.DURATION_MINUTES, new DraftField(IntNode.valueOf(60), FieldSource.USER, null, false));
        d.getFields().put(DraftSlotRegistry.ANCHOR, new DraftField(TextNode.valueOf(DraftSlotRegistry.ANCHOR_FIRST_CLASS_OF_DAY),
                anchorConfirmed ? FieldSource.USER : FieldSource.INFERRED, null, !anchorConfirmed));
        d.getFields().put(DraftSlotRegistry.START_DATE, new DraftField(TextNode.valueOf(TODAY.toString()), FieldSource.SYSTEM,
                DraftSlotRegistry.REASON_CURRENT_DATE, false));
        d.getFields().put(DraftSlotRegistry.END_DATE, new DraftField(TextNode.valueOf(SEMESTER_END.toString()), FieldSource.DB,
                DraftSlotRegistry.REASON_ACTIVE_SEMESTER_END, false));
        d.setMissingRequired(List.of());
        return d;
    }

    static DraftState openSchedule(long id, LocalDateTime createdAt) {
        DraftState d = new DraftState();
        d.setDraftId(id);
        d.setUserId(USER_ID);
        d.setConversationId(CONVERSATION_ID);
        d.setDraftGroupId(31L);
        d.retype(DraftType.CREATE_SCHEDULE);
        d.setLabel("근무 전 이동 블록");
        d.setCreatedAt(createdAt);
        d.getFields().put(DraftSlotRegistry.TITLE, new DraftField(TextNode.valueOf("이동시간"), FieldSource.DEFAULT,
                DraftSlotRegistry.REASON_DRAFT_LABEL, false));
        d.getFields().put(DraftSlotRegistry.DURATION_MINUTES, new DraftField(IntNode.valueOf(60), FieldSource.USER, null, false));
        d.getFields().put(DraftSlotRegistry.ANCHOR, new DraftField(TextNode.valueOf(DraftSlotRegistry.ANCHOR_BEFORE_EACH_WORK_SHIFT),
                FieldSource.INFERRED, null, false));
        d.setMissingRequired(List.of());
        return d;
    }

    static DraftState openPeriodPlan(long id, LocalDateTime createdAt) {
        DraftState d = new DraftState();
        d.setDraftId(id);
        d.setUserId(USER_ID);
        d.setConversationId(CONVERSATION_ID);
        d.setDraftGroupId(id);
        d.retype(DraftType.CREATE_PERIOD_PLAN);
        d.setLabel("이번 학기 계획");
        d.setCreatedAt(createdAt);
        d.getFields().put(DraftSlotRegistry.START_DATE, new DraftField(TextNode.valueOf(TODAY.toString()), FieldSource.USER, null, false));
        d.getFields().put(DraftSlotRegistry.END_DATE, new DraftField(TextNode.valueOf("2026-09-20"), FieldSource.USER, null, false));
        d.getFields().put(DraftSlotRegistry.INTENSITY, new DraftField(TextNode.valueOf("NORMAL"), FieldSource.INFERRED, null, true));
        d.setMissingRequired(List.of());
        return d;
    }

    static DraftTurnResolver.Input input(List<DraftState> open, AiTurnStructured structured, String reply,
                                         boolean userMentionedFirst, DraftFacts facts) {
        return input(open, structured, reply, userMentionedFirst, facts, DraftFactsSource.fixed(facts));
    }

    /** 요청 기간에 따라 다른 사실을 주는 턴(기본 조회 기간 밖 요청 재현용). */
    static DraftTurnResolver.Input input(List<DraftState> open, AiTurnStructured structured, String reply,
                                         boolean userMentionedFirst, DraftFacts facts, DraftFactsSource source) {
        return new DraftTurnResolver.Input(USER_ID, CONVERSATION_ID, open, structured, reply, userMentionedFirst,
                facts, source, OM);
    }

    /** 1회성 근무 기준 draft. anchor는 호출자가 넣는다. */
    static DraftState openWorkSchedule(long id, LocalDateTime createdAt, String label, String anchor,
                                       LocalDate from, LocalDate to) {
        DraftState d = new DraftState();
        d.setDraftId(id);
        d.setUserId(USER_ID);
        d.setConversationId(CONVERSATION_ID);
        d.setDraftGroupId(id);
        d.retype(DraftType.CREATE_SCHEDULE);
        d.setLabel(label);
        d.setCreatedAt(createdAt);
        d.getFields().put(DraftSlotRegistry.TITLE, new DraftField(TextNode.valueOf(label), FieldSource.USER, null, false));
        d.getFields().put(DraftSlotRegistry.DURATION_MINUTES, new DraftField(IntNode.valueOf(60), FieldSource.USER, null, false));
        if (anchor != null) {
            d.getFields().put(DraftSlotRegistry.ANCHOR, new DraftField(TextNode.valueOf(anchor), FieldSource.USER, null, false));
        }
        if (from != null && to != null) {
            ObjectNode range = OM.createObjectNode();
            range.put("from", from.toString());
            range.put("to", to.toString());
            d.getFields().put(DraftSlotRegistry.DATE_RANGE, new DraftField(range, FieldSource.USER, null, false));
        }
        d.setMissingRequired(List.of());
        return d;
    }

    static DraftState byRef(DraftTurnResolver.Outcome outcome, String ref) {
        return outcome.drafts().stream().filter(d -> d.matches(ref)).findFirst().orElseThrow();
    }
}
