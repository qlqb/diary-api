package com.jungwoo.project.memo.ai.draft;

import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver.WorkShift;

import java.time.DayOfWeek;
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
 *       {@code [열려 있는 작업]} + {@code [관련 실제 데이터]} 블록. 최대 {@link #MAX_CHARS}자.
 * </ul>
 */
public final class DraftPromptBuilder {

    /** draft 블록이 선점하는 입력 예산(문자). 나머지를 기존 배분 로직(ContextSnapshotService)에 넘긴다. */
    public static final int MAX_CHARS = 1200;

    /** 이 개수를 넘는 OPEN draft는 "#id 라벨" 한 줄로만 싣는다. 최근 갱신순. */
    public static final int MAX_DETAILED_DRAFTS = 5;

    public static final String OPEN_DRAFTS_HEADER = "[열려 있는 작업]";
    public static final String FACTS_HEADER = "[관련 실제 데이터]";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MONTH_DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

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
                뭉치지 않는다. 예: "수업 전 이동 반복으로, 근무 전 이동 1회성으로" → CREATE_ROUTINE 하나
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
                근무 데이터로 후보를 만든다. "첫 수업 1시간 전"처럼 다른 일정에 상대적인 시각은 네가
                요일·시각으로 계산하지 말고 anchor 필드로 낸다.
            31. draft 필드 이름과 값(정확히 이 이름만 쓴다):
                - CREATE_ROUTINE: title(문자열), anchor("FIRST_CLASS_OF_DAY" — 그날 첫 수업 전),
                  daysOfWeek(["MONDAY".."SUNDAY"], anchor가 없을 때), startTime("HH:mm", anchor가 없을 때),
                  durationMinutes(정수 분), startDate("YYYY-MM-DD"), endDate("YYYY-MM-DD")
                - CREATE_SCHEDULE: title, anchor("BEFORE_EACH_WORK_SHIFT" — 등록된 근무 전마다),
                  date("YYYY-MM-DD", anchor가 없을 때), startTime("HH:mm", anchor가 없을 때),
                  durationMinutes, dateRange({"from":"YYYY-MM-DD","to":"YYYY-MM-DD"}, anchor일 때 선택)
                - CREATE_PERIOD_PLAN: startDate, endDate, intensity("LIGHT"|"NORMAL"|"FOCUSED")
                "1시간"은 durationMinutes=60이다. 날짜는 시스템 프롬프트의 현재 시간 정보를 기준으로 실제 날짜로 적는다.
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

    /** OPEN draft가 없으면 빈 문자열. 있으면 두 블록을 합쳐 최대 {@link #MAX_CHARS}자. */
    public static String renderOpenDrafts(List<DraftState> openDrafts, DraftFacts facts) {
        if (openDrafts == null || openDrafts.isEmpty()) {
            return "";
        }
        String factsBlock = renderFacts(facts);
        int detailed = Math.min(openDrafts.size(), MAX_DETAILED_DRAFTS);
        String block = render(openDrafts, detailed, factsBlock);
        while (block.length() > MAX_CHARS && detailed > 1) {
            detailed--;
            block = render(openDrafts, detailed, factsBlock);
        }
        if (block.length() > MAX_CHARS) {
            block = block.substring(0, MAX_CHARS - 1) + "\n";
        }
        return block;
    }

    private static String render(List<DraftState> drafts, int detailed, String factsBlock) {
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
        if (!factsBlock.isEmpty()) {
            sb.append(factsBlock);
        }
        return sb.toString();
    }

    private static String renderValue(DraftField field) {
        if (field.isNull()) {
            return "null";
        }
        return field.value().isTextual() ? field.value().asText() : field.value().toString();
    }

    /** 요일별 첫 수업과 앞으로의 근무. 둘 다 없으면 빈 문자열. */
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
        if (!facts.workShifts().isEmpty()) {
            StringBuilder line = new StringBuilder("등록된 근무: ");
            int shown = 0;
            for (WorkShift shift : facts.workShifts()) {
                if (shown == 10) {
                    line.append(" 외 ").append(facts.workShifts().size() - shown).append('건');
                    break;
                }
                if (shown > 0) {
                    line.append(", ");
                }
                line.append(shift.startAt().format(MONTH_DAY_FMT)).append('(')
                        .append(shift.startAt().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN))
                        .append(") ").append(shift.startAt().format(TIME_FMT));
                shown++;
            }
            lines.add(line.toString());
        } else if (facts.workRangeFrom() != null) {
            lines.add("등록된 근무: 없음(" + facts.workRangeFrom() + "~" + facts.workRangeTo() + ")");
        }
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(FACTS_HEADER).append('\n');
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
