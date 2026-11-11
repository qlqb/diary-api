package com.jungwoo.project.memo.ai.change;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 모델이 낸 도구 인자 읽기. 틀리면 어느 필드인지 말하는 IllegalArgumentException — 도구 결과(INVALID_ARGS)로 모델에게 돌려준다. */
public final class ToolArgs {

    private ToolArgs() {
    }

    public static Long id(JsonNode args, String field) {
        JsonNode n = args.path(field);
        if (!n.canConvertToLong()) {
            throw new IllegalArgumentException(field + "는 정수 id여야 한다");
        }
        return n.asLong();
    }

    public static Long optionalId(JsonNode args, String field) {
        return args.hasNonNull(field) ? id(args, field) : null;
    }

    public static LocalDate date(JsonNode args, String field) {
        LocalDate d = optionalDate(args, field);
        if (d == null) {
            throw new IllegalArgumentException(field + "(YYYY-MM-DD)가 필요하다");
        }
        return d;
    }

    public static LocalDate optionalDate(JsonNode args, String field) {
        String v = text(args, field);
        try {
            return v == null ? null : LocalDate.parse(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + "는 YYYY-MM-DD여야 한다");
        }
    }

    public static LocalTime optionalTime(JsonNode args, String field) {
        String v = text(args, field);
        try {
            return v == null ? null : "24:00".equals(v) ? LocalTime.MIDNIGHT : LocalTime.parse(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + "는 HH:mm이어야 한다");
        }
    }

    public static LocalDateTime optionalDateTime(JsonNode args, String field) {
        String v = text(args, field);
        try {
            return v == null ? null : LocalDateTime.parse(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + "는 YYYY-MM-DDTHH:mm이어야 한다");
        }
    }

    public static LocalDateTime dateTime(JsonNode args, String field) {
        LocalDateTime v = optionalDateTime(args, field);
        if (v == null) {
            throw new IllegalArgumentException(field + "(YYYY-MM-DDTHH:mm)가 필요하다");
        }
        return v;
    }

    public static Integer optionalInt(JsonNode args, String field) {
        JsonNode n = args.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.canConvertToInt()) {
            throw new IllegalArgumentException(field + "는 정수여야 한다");
        }
        return n.asInt();
    }

    public static String text(JsonNode args, String field) {
        JsonNode n = args.path(field);
        return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText().trim();
    }

    public static Set<DayOfWeek> days(JsonNode args, String field) {
        Set<DayOfWeek> out = new LinkedHashSet<>();
        args.path(field).forEach(d -> {
            try {
                out.add(DayOfWeek.valueOf(d.asText().trim().toUpperCase(Locale.ROOT)));
            } catch (Exception e) {
                throw new IllegalArgumentException(field + "는 MONDAY..SUNDAY 목록이어야 한다");
            }
        });
        return out;
    }
}
