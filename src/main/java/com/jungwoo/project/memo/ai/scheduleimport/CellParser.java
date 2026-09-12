package com.jungwoo.project.memo.ai.scheduleimport;

import java.time.LocalTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 근무표 셀 하나를 읽는다. 순수 함수이고 LLM에 의존하지 않는다.
 *
 * <p>이 클래스가 있는 이유는 원칙 하나 때문이다 — <b>모델은 받아쓰기만 한다.</b> 시간 계산이
 * 여기 있으면 같은 입력에 늘 같은 결과가 나오고, 틀렸을 때 어느 규칙이 틀렸는지 짚을 수 있다.
 * 모델에게 맡기면 "이번에는 왜 다르게 읽었지"에 답할 방법이 없다.
 *
 * <p>모르는 것은 모른다고 한다. 범례에 없는 코드를 그럴듯한 시간으로 채우지 않는다.
 */
public final class CellParser {

    /** 쉬는 날을 뜻하는 표기. 사람이 손으로 쓰는 근무표에서 실제로 보이는 것들이다. */
    private static final Set<String> OFF_TOKENS = Set.of(
            "", "-", "–", "—", "D/O", "DO", "OFF", "휴", "휴무", "X", "／", "/");

    /**
     * 시간 구간. {@code 17~23}, {@code 9:30-13:00}, {@code 9시~13시}, {@code 09~13}을 모두 받는다.
     *
     * <p>구분자로 물결·하이픈·en dash를 받는다 — 손으로 쓴 표와 엑셀 캡처에서 셋이 섞여 나온다.
     */
    private static final Pattern RANGE = Pattern.compile(
            "^(\\d{1,2})\\s*(?::\\s*(\\d{1,2}))?\\s*시?\\s*[~\\-–—]\\s*(\\d{1,2})\\s*(?::\\s*(\\d{1,2}))?\\s*시?$");

    private CellParser() {
    }

    /**
     * @param legend 표에 적힌 범례. 없으면 빈 맵. 키는 대소문자를 가리지 않는다 —
     *               같은 표 안에서 {@code OP}와 {@code op}가 섞여 나온다
     */
    public static ParsedCell parse(String rawCell, Map<String, String> legend) {
        String raw = rawCell == null ? "" : rawCell.trim();

        if (OFF_TOKENS.contains(raw.toUpperCase(Locale.ROOT))) {
            return ParsedCell.off(raw);
        }

        ParsedCell direct = parseRange(raw, raw, null);
        if (direct != null) {
            return direct;
        }

        /*
         * 범례 치환. 범례 값도 시간 구간이어야 한다 — "OP: 오픈 근무"처럼 설명만 적힌
         * 범례는 시간을 알려주지 않으므로 모르는 칸으로 남긴다. 사용자에게 물으면 된다.
         */
        String resolved = lookup(legend, raw);
        if (resolved != null) {
            ParsedCell viaLegend = parseRange(resolved.trim(), raw, raw);
            if (viaLegend != null) {
                return viaLegend;
            }
        }

        return ParsedCell.unresolved(raw);
    }

    private static String lookup(Map<String, String> legend, String raw) {
        if (legend == null || raw.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : legend.entrySet()) {
            if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(raw)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * @param text 시간 구간으로 읽을 문자열(셀 원문이거나 범례가 풀어 준 값)
     * @param raw  사용자에게 보여줄 원문. 범례를 거쳤으면 코드 쪽이다
     * @param code 범례를 거쳤으면 그 코드, 아니면 null
     */
    private static ParsedCell parseRange(String text, String raw, String code) {
        Matcher matcher = RANGE.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        Integer startHour = hour(matcher.group(1));
        Integer startMinute = minute(matcher.group(2));
        Integer endHour = hour(matcher.group(3));
        Integer endMinute = minute(matcher.group(4));
        if (startHour == null || startMinute == null || endHour == null || endMinute == null) {
            return null;
        }
        // 시작이 24시일 수는 없다. 종료의 24:00만 자정을 뜻한다.
        if (startHour == 24) {
            return null;
        }

        LocalTime start = LocalTime.of(startHour, startMinute);
        /*
         * 종료의 24:00과 00:00은 둘 다 자정이다. LocalTime에 24시가 없으므로 00:00으로 적고
         * "다음 날"이라는 사실을 crossesMidnight으로 따로 들고 간다 — 00:00 하나만 남기면
         * 그 일정이 그날 새벽인지 다음 날 새벽인지 구분되지 않는다.
         */
        boolean endsAtMidnight = endHour == 24 || (endHour == 0 && endMinute == 0);
        LocalTime end = endsAtMidnight ? LocalTime.MIDNIGHT : LocalTime.of(endHour % 24, endMinute);

        /*
         * 종료가 시작보다 이르거나 같으면 다음 날이다. 야간 근무(22~02, 15~00)가 실제 데이터에
         * 있다. 다만 완전히 같은 시각(17~17)은 길이 0이라 근무로 볼 수 없다 — 표를 잘못 읽었을
         * 가능성이 높으므로 모르는 칸으로 남기고 사용자에게 묻는다.
         */
        boolean crossesMidnight = endsAtMidnight || !end.isAfter(start);
        if (!endsAtMidnight && end.equals(start)) {
            return null;
        }

        return ParsedCell.work(raw, start, end, crossesMidnight, code);
    }

    private static Integer hour(String value) {
        int parsed = Integer.parseInt(value);
        return parsed >= 0 && parsed <= 24 ? parsed : null;
    }

    private static Integer minute(String value) {
        if (value == null) {
            return 0;
        }
        int parsed = Integer.parseInt(value);
        return parsed >= 0 && parsed <= 59 ? parsed : null;
    }
}
