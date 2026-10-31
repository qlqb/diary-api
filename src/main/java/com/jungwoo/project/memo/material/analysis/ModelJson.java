package com.jungwoo.project.memo.material.analysis;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 모델이 낸 JSON을 너그럽게 읽기 위한 작은 도구. 스키마의 뜻은 바꾸지 않고 모양만 받아 준다.
 *
 * <p>실측(2026-09-13)에서 모델이 코드 펜스(```json … ```)로 감싸거나 id를 "S66"·"#7"처럼 접두어가 붙은
 * 문자열로 내는 경우가 있었다. 그때마다 청크를 통째로 버리고 다시 부르면 호출만 늘어난다. 접두어를 떼고
 * 숫자만 읽되, 숫자가 아니면 null로 두어 뒤의 검증이 버리게 한다 — 여기서 값을 지어내지 않는다.
 */
public final class ModelJson {

    private ModelJson() {
    }

    /** 코드 펜스를 벗기고 첫 '{'부터 마지막 '}'까지만 남긴다. JSON 객체가 없으면 null. */
    public static String unwrapObject(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            text = firstNewline < 0 ? "" : text.substring(firstNewline + 1);
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** 숫자 또는 "S12"/"#12"/"A7" 같은 문자열에서 id를 읽는다. 없거나 숫자가 아니면 null. */
    public static Long longOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            String digits = node.asText().replaceAll("[^0-9]", "");
            if (digits.isEmpty() || digits.length() > 18) {
                return null;
            }
            return Long.parseLong(digits);
        }
        return null;
    }

    public static Long longOf(JsonNode parent, String field) {
        return parent == null ? null : longOf(parent.get(field));
    }

    public static List<Long> longsOf(JsonNode parent, String field) {
        List<Long> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        JsonNode array = parent.get(field);
        if (array == null || array.isNull()) {
            return out;
        }
        if (array.isArray()) {
            for (JsonNode one : array) {
                Long value = longOf(one);
                if (value != null) {
                    out.add(value);
                }
            }
        } else {
            Long value = longOf(array);
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    public static String textOf(JsonNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.isTextual() ? node.asText() : node.toString();
        return text.isBlank() ? null : text.trim();
    }
}
