package com.jungwoo.project.memo.ai.draft;

import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver.WorkShift;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 프롬프트의 draft 부분. 순수 자바.
 *
 * <ul>
 *   <li>{@link #SYSTEM_RULES}: 시스템 프롬프트 뒤에 항상 붙는 규칙과 구조화 출력 추가 필드.
 *       첫 턴(OPEN draft 없음)에도 모델이 create를 낼 수 있어야 하므로 항상 붙인다.
 *   <li>{@link #renderOpenDrafts}: OPEN draft가 하나라도 있는 턴에 사용자 프롬프트 맨 앞에 붙는
 *       {@code [열려 있는 작업]} + {@code [관련 실제 데이터]} 블록.
 * </ul>
 *
 * <p><b>예산은 둘로 나눈다.</b> 예전에는 두 블록을 이어 붙인 뒤 전체를 잘랐는데, 사실 블록이
 * 뒤에 있어서 draft가 많으면 근무 목록이 통째로 사라졌다 — 모델은 종료 시각을 못 본 채
 * "근무 후 이동"을 만들라는 요청을 받고, 볼 수 없는 값을 사용자에게 물었다. 이제
 * {@link #FACTS_MAX_CHARS}는 사실 블록이 먼저 가져가고, 남는 것을 draft 상세가 쓴다.
 * 잘리는 쪽은 항상 draft 목록이고, 그것도 "#id 라벨" 한 줄까지만 줄어든다.
 */
public final class DraftPromptBuilder {

    /** draft 블록이 선점하는 입력 예산(문자). 나머지를 기존 배분 로직(ContextSnapshotService)에 넘긴다. */
    public static final int MAX_CHARS = 1200;

    /**
     * 그중 사실 블록이 먼저 가져가는 몫. 근무 한 건이 한 줄(약 40자)이라 10건 + 머리글이 들어간다.
     * 사실이 draft 상세보다 먼저 확보돼야 하는 이유는, 모델이 채울 수 없는 값을 사용자에게
     * 되묻는 일이 사실 누락에서 나오기 때문이다.
     */
    public static final int FACTS_MAX_CHARS = 640;

    /** 이 개수를 넘는 OPEN draft는 "#id 라벨" 한 줄로만 싣는다. 최근 갱신순. */
    public static final int MAX_DETAILED_DRAFTS = 5;

    /** 사실 블록에 실을 근무 줄 수. 넘으면 생략을 명시한다 — 서버 계산은 전체가 대상이다. */
    public static final int MAX_SHIFT_LINES = 10;

    public static final String OPEN_DRAFTS_HEADER = "[열려 있는 작업]";
    public static final String FACTS_HEADER = "[관련 실제 데이터]";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 시스템 프롬프트에 추가하는 규칙. 기존 원칙 번호(1~22)를 잇는다.
     */
    public static final String SYSTEM_RULES = """

            [진행 중 요청(draft) 규칙 — 원칙 23~31]
            사용자 메시지 앞에 [열려 있는 작업]이 있으면 그것은 서버가 들고 있는 "아직 만들지 않은 일정
            요청"의 현재 상태다. 각 줄의 값은 이미 확정된 것이니 다시 묻지 않는다. 너는 draft를 직접
            저장하지 않는다 — 어느 draft를 대상으로 무엇을 바꿀지(routing, draftOps)만 제안하고,
            적용·저장·준비 여부 판정은 서버가 한다.
            23. 한 발화에 서로 다른 일정 요청이 둘 이상이면 draft를 그만큼 routing.create한다. 하나로
                뭉치지 않는다. 예: "수업 전 이동 반복으로, 근무 후 이동 1회성으로" → CREATE_ROUTINE 하나
                + CREATE_SCHEDULE 하나(둘째는 sameGroupAs로 첫째 그룹에 붙인다).
            24. 사용자가 어느 작업을 말하는지 [열려 있는 작업]의 라벨·필드로 판단해 routing.targets에
                넣는다. "둘 다"면 둘 다. 이번 턴에 만든 draft는 draftOps에서 "new-0", "new-1"로 참조한다.
            25. 요청에 범위·대상·기준이 명시되지 않았으면 source=INFERRED로 내고, 해석이 갈릴 수 있으면
                confirmationRequired=true로 낸다. 사용자가 말로 명시한 값만 source=USER다. 너는 USER와
                INFERRED만 낼 수 있다 — DB/SYSTEM/DEFAULT는 서버가 채우는 출처라 네가 쓰면 버려진다.
            26. [열려 있는 작업]에 이미 있는 값을 다시 묻지 않는다. 필수 누락이 "없음"이면 그 draft는 더
                물을 것이 없다.
            27. actionHint=ASK일 때 질문은 하나, 닫힌 선택지 형태로 쓴다("수업 전마다요, 아니면 그날 첫
                수업 전만요?"). 질문은 reply에 쓰고 물음표로 끝낸다.
            28. 사용자가 "그냥 만들어줘", "지금 만들어", "바로 해줘"처럼 즉시 생성을 요구하면
                userTriggered=true.
            29. 의도가 바뀌면 draftOps에 RETYPE을 낸다. CREATE_ROUTINE/CREATE_SCHEDULE에서는 강도(intensity)
                를 절대 묻지 않는다. 이동·준비·휴식 블록 생성 요청은 CREATE_PERIOD_PLAN이 아니다 —
                반복이면 CREATE_ROUTINE, 특정 날짜의 1회성이면 CREATE_SCHEDULE이다.
            30. draft 대상 요청은 scheduleSuggestions로 내지 않는다. draft가 준비되면 서버가 실제 시간표·
                근무 데이터로 후보를 만든다. "첫 수업 1시간 전", "근무 끝나고 바로"처럼 다른 일정에
                상대적인 시각은 네가 요일·날짜·시각으로 계산하지 말고 anchor 필드로 낸다. 개별 근무의
                시작·종료 시각을 네가 옮겨 적거나 추측해서 date/startTime에 넣지 않는다 — 그 계산은
                서버가 [관련 실제 데이터]의 원본으로 한다.
            31. draft 필드 이름과 값(정확히 이 이름만 쓴다):
                - CREATE_ROUTINE: title(문자열), anchor("FIRST_CLASS_OF_DAY" — 그날 첫 수업 전),
                  daysOfWeek(["MONDAY".."SUNDAY"], anchor가 없을 때), startTime("HH:mm", anchor가 없을 때),
                  durationMinutes(정수 분), startDate("YYYY-MM-DD"), endDate("YYYY-MM-DD")
                - CREATE_SCHEDULE: title,
                  anchor("BEFORE_EACH_WORK_SHIFT" — 등록된 근무가 시작하기 전마다 /
                         "AFTER_EACH_WORK_SHIFT" — 등록된 근무가 끝난 직후마다),
                  date("YYYY-MM-DD", anchor가 없을 때), startTime("HH:mm", anchor가 없을 때),
                  durationMinutes, dateRange({"from":"YYYY-MM-DD","to":"YYYY-MM-DD"}, anchor일 때 선택)
                - CREATE_PERIOD_PLAN: startDate, endDate, intensity("LIGHT"|"NORMAL"|"FOCUSED")
                "1시간"은 durationMinutes=60이다. 날짜는 시스템 프롬프트의 현재 시간 정보를 기준으로 실제 날짜로 적는다.
                "근무 후", "퇴근하고", "끝나고 나서"는 AFTER_EACH_WORK_SHIFT다. "근무 전", "출근 전",
                "가기 전에"는 BEFORE_EACH_WORK_SHIFT다. "이번 주"처럼 기간을 말하면 그 실제 날짜를
                dateRange로 낸다 — 기간을 빼먹으면 서버가 기본 2주로 만든다.
                "지금부터"는 startDate=오늘(source=USER)이다. 학기 종료일은 네가 추측하지 않는다 —
                사용자가 날짜를 말했을 때만 endDate를 USER로 낸다.

            구조화 JSON에 아래 네 필드를 추가한다(다른 필드는 그대로):
              "routing": {
                "targets": [이번 발화가 대상으로 하는 기존 draft의 번호(정수) ...],
                "create": [ {"draftType": "CREATE_ROUTINE"|"CREATE_SCHEDULE"|"CREATE_PERIOD_PLAN",
                             "label": "짧은 라벨(예: 수업 전 이동 루틴)",
                             "sameGroupAs": 기존 draft 번호 또는 "new-0" 또는 null} ... ]
              },
              "draftOps": [
                {"draftId": 31 또는 "new-0", "op": "SET", "field": "필드명", "value": 값,
                 "source": "USER"|"INFERRED", "confirmationRequired": true|false},
                {"draftId": 31, "op": "CLEAR", "field": "필드명"},
                {"draftId": 31, "op": "RETYPE", "draftType": "CREATE_ROUTINE"},
                {"draftId": 32, "op": "CANCEL"}
              ],
              "actionHint": "ASK"|"PROPOSE"|"CHAT",
              "userTriggered": true|false
            일정 요청과 무관한 턴이면 routing.targets와 create, draftOps는 빈 배열이고 actionHint=CHAT이다.
            """;

    private DraftPromptBuilder() {
    }

    /** OPEN draft가 없으면 빈 문자열. 있으면 사실 블록 + draft 블록을 합쳐 최대 {@link #MAX_CHARS}자. */
    public static String renderOpenDrafts(List<DraftState> openDrafts, DraftFacts facts) {
        if (openDrafts == null || openDrafts.isEmpty()) {
            return "";
        }
        String factsBlock = renderFacts(facts);
        // 사실 블록이 예산을 먼저 가져간다. 자를 때도 줄 단위로만 자른다 — 근무 한 줄의 종료
        // 시각만 잘린 문자열은 모델에게 "종료 시각이 없다"와 같아진다.
        factsBlock = trimToWholeLines(factsBlock, FACTS_MAX_CHARS);
        int draftBudget = Math.max(0, MAX_CHARS - factsBlock.length());

        int detailed = Math.min(openDrafts.size(), MAX_DETAILED_DRAFTS);
        String draftsBlock = renderDrafts(openDrafts, detailed);
        while (draftsBlock.length() > draftBudget && detailed > 0) {
            detailed--;
            draftsBlock = renderDrafts(openDrafts, detailed);
        }
        if (draftsBlock.length() > draftBudget) {
            draftsBlock = trimToWholeLines(draftsBlock, draftBudget);
        }
        return draftsBlock + factsBlock;
    }

    private static String renderDrafts(List<DraftState> drafts, int detailed) {
        StringBuilder sb = new StringBuilder(OPEN_DRAFTS_HEADER).append('\n');
        for (int i = 0; i < drafts.size(); i++) {
            DraftState draft = drafts.get(i);
            sb.append('#').append(draft.refId()).append(' ').append(draft.getType().name())
                    .append(" \"").append(draft.getLabel()).append('"');
            if (draft.getDraftGroupId() != null) {
                sb.append(" (그룹 #").append(draft.getDraftGroupId()).append(')');
            }
            sb.append('\n');
            if (i >= detailed) {
                continue;
            }
            for (Map.Entry<String, DraftField> entry : draft.getFields().entrySet()) {
                DraftField field = entry.getValue();
                sb.append("  ").append(entry.getKey()).append(": ").append(renderValue(field))
                        .append(" (").append(field.source().name());
                if (field.confirmationRequired()) {
                    sb.append(", 확인 필요");
                }
                sb.append(")\n");
            }
            sb.append("  필수 누락: ")
                    .append(draft.getMissingRequired().isEmpty() ? "없음" : String.join(", ", draft.getMissingRequired()))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String renderValue(DraftField field) {
        if (field.isNull()) {
            return "null";
        }
        return field.value().isTextual() ? field.value().asText() : field.value().toString();
    }

    /**
     * 요일별 첫 수업과 조회 기간의 근무. 둘 다 없으면 빈 문자열.
     *
     * <p>근무는 <b>식별자와 시작·종료를 모두</b> 싣는다. 종료 시각이 없으면 "근무 후 이동"을
     * 만들라는 요청에 모델이 답할 근거가 없어, 서버가 이미 아는 값을 사용자에게 되묻게 된다.
     * 조회 상태와 기간도 함께 적는다 — "이 기간에 근무가 없다"와 "조회하지 못했다"를 모델이
     * 구분할 수 있어야 한다.
     */
    static String renderFacts(DraftFacts facts) {
        if (facts == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        if (!facts.firstClassByDay().isEmpty()) {
            StringBuilder line = new StringBuilder();
            boolean first = true;
            for (DayOfWeek day : DayOfWeek.values()) {
                LocalTime time = facts.firstClassByDay().get(day);
                if (time == null) {
                    continue;
                }
                line.append(first ? "" : " / ").append(day.getDisplayName(TextStyle.SHORT, Locale.KOREAN))
                        .append(first ? " 첫 수업 " : " ").append(time.format(TIME_FMT));
                first = false;
            }
            lines.add(line.toString());
        }
        facts.semesterEnd().ifPresent(end -> lines.add("수업 루틴 종강일: " + end));
        lines.addAll(workLines(facts));
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(FACTS_HEADER).append('\n');
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static List<String> workLines(DraftFacts facts) {
        List<String> lines = new ArrayList<>();
        LocalDate from = facts.workRangeFrom();
        LocalDate to = facts.workRangeTo();
        String range = " · 대상 기간 " + from + "~" + to;

        if (facts.workLookupFailed()) {
            // 근무가 없다고 쓰지 않는다. 없는 것과 못 읽은 것은 사용자가 할 일이 다르다.
            lines.add("근무 조회 실패" + range + " (근무가 없다는 뜻이 아니다)");
            return lines;
        }
        List<WorkShift> shifts = facts.workShifts();
        List<WorkShift> incomplete = facts.incompleteShifts();
        if (shifts.isEmpty() && incomplete.isEmpty()) {
            lines.add("근무 조회 성공" + range + " · 등록된 근무 없음");
            return lines;
        }
        lines.add("근무 조회 성공" + range);
        int shown = 0;
        for (WorkShift shift : shifts) {
            if (shown == MAX_SHIFT_LINES) {
                break;
            }
            lines.add("근무 #" + shift.commitmentId() + ": "
                    + shift.startAt().format(DATE_TIME_FMT) + " → " + shift.endAt().format(DATE_TIME_FMT));
            shown++;
        }
        if (shifts.size() > shown) {
            // 서버는 전부를 계산한다. 모델이 본 만큼만 만들어진다고 오해하면 안 된다.
            lines.add("(근무 총 " + shifts.size() + "건 중 " + shown + "건만 표시. 서버 계산은 전체 대상)");
        }
        for (WorkShift shift : incomplete) {
            lines.add("근무 #" + shift.commitmentId() + ": " + shift.startAt().format(DATE_TIME_FMT)
                    + " → 종료 시각 없음(뒤쪽 이동 계산 불가)");
        }
        return lines;
    }

    /**
     * 마지막 줄바꿈까지만 남긴다. 문자 수로 자르면 근무 한 줄이 "→ 2026-09-08 2"에서 끊겨,
     * 모델은 그것을 종료 시각이 없는 근무로 읽는다.
     */
    static String trimToWholeLines(String block, int max) {
        if (block == null || block.isEmpty() || block.length() <= max) {
            return block == null ? "" : block;
        }
        int cut = block.lastIndexOf('\n', max);
        return cut <= 0 ? "" : block.substring(0, cut + 1);
    }
}
