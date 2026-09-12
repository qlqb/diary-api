package com.jungwoo.project.memo.ai.draft;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * draft 필드 하나. ai_conversation_drafts.fields JSON의 값 하나와 1:1이다.
 *
 * @param value                JSON 그대로(숫자·문자열·배열·객체). 타입 해석은 읽는 쪽이 한다
 * @param source               어디서 왔는가
 * @param reason               왜 이 값인가(resolver 이름·정책 이름). 화면 fieldNotes의 reason이 된다
 * @param confirmationRequired 사용자 확인 없이 진행하면 안 되는가
 */
public record DraftField(JsonNode value, FieldSource source, String reason, boolean confirmationRequired) {

    public DraftField {
        if (source == null) {
            throw new IllegalArgumentException("source는 필수다");
        }
    }

    public DraftField withConfirmationRequired(boolean required) {
        return required == confirmationRequired ? this : new DraftField(value, source, reason, required);
    }

    public boolean isNull() {
        return value == null || value.isNull() || value.isMissingNode();
    }

    public String asText() {
        return isNull() ? null : value.isTextual() ? value.asText() : value.toString();
    }

    public Integer asInt() {
        if (isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        try {
            return Integer.valueOf(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Boolean asBoolean() {
        if (isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return Boolean.valueOf(value.asText().trim());
    }

    public LocalDate asLocalDate() {
        if (isNull()) {
            return null;
        }
        try {
            return LocalDate.parse(value.asText().trim());
        } catch (Exception e) {
            return null;
        }
    }

    public LocalTime asLocalTime() {
        if (isNull()) {
            return null;
        }
        try {
            return LocalTime.parse(value.asText().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** ["MONDAY", ...] 또는 "MONDAY" 하나. 읽을 수 없는 항목은 버린다. */
    public List<DayOfWeek> asDaysOfWeek() {
        List<DayOfWeek> days = new ArrayList<>();
        if (isNull()) {
            return days;
        }
        if (value.isArray()) {
            for (JsonNode node : value) {
                DayOfWeek day = parseDay(node.asText());
                if (day != null && !days.contains(day)) {
                    days.add(day);
                }
            }
        } else {
            DayOfWeek day = parseDay(value.asText());
            if (day != null) {
                days.add(day);
            }
        }
        return days;
    }

    /** {"from":"YYYY-MM-DD","to":"YYYY-MM-DD"}. 둘 중 하나라도 없으면 null. */
    public LocalDate[] asDateRange() {
        if (isNull() || !value.isObject()) {
            return null;
        }
        try {
            LocalDate from = LocalDate.parse(value.path("from").asText());
            LocalDate to = LocalDate.parse(value.path("to").asText());
            return new LocalDate[]{from, to};
        } catch (Exception e) {
            return null;
        }
    }

    private static DayOfWeek parseDay(String text) {
        try {
            return DayOfWeek.valueOf(text.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}
