package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.learning.events.EventVocabulary.ObjectKind;

/**
 * 이벤트가 가리키는 원천 하나(설계 20번 §5.1). ref 형식은 {@code s:{sectionId}} · {@code m:{materialId}} ·
 * {@code t:{bookRefId}:{keyHash}:{keyLine}} · {@code c:{sessionId}} · COURSE는 {@code -}.
 *
 * @param from 범위 시작(구간의 인쇄 쪽 등). 없으면 null
 * @param to   범위 끝. 없으면 null
 */
public record SourceRef(ObjectKind kind, String ref, Integer from, Integer to) {

    public static final String COURSE_REF = "-";

    public SourceRef {
        if (kind == null || ref == null || ref.isBlank() || ref.length() > 200) {
            throw new IllegalArgumentException("원천 참조가 비었거나 너무 길다");
        }
    }

    public static SourceRef section(long sectionId) {
        return new SourceRef(ObjectKind.SECTION, "s:" + sectionId, null, null);
    }

    public static SourceRef section(long sectionId, Integer from, Integer to) {
        return new SourceRef(ObjectKind.SECTION, "s:" + sectionId, from, to);
    }

    public static SourceRef material(long materialId) {
        return new SourceRef(ObjectKind.MATERIAL, "m:" + materialId, null, null);
    }

    public static SourceRef tocEntry(long bookRefId, String keyHash, int keyLine) {
        return new SourceRef(ObjectKind.TOC_ENTRY, "t:" + bookRefId + ":" + keyHash + ":" + keyLine, null, null);
    }

    public static SourceRef session(long sessionId) {
        return new SourceRef(ObjectKind.SESSION, "c:" + sessionId, null, null);
    }

    public static SourceRef course() {
        return new SourceRef(ObjectKind.COURSE, COURSE_REF, null, null);
    }

    /** s:/m:/c: 뒤의 id. 형식이 다르면 null. */
    public Long numericId() {
        int colon = ref.indexOf(':');
        if (colon < 0 || ref.indexOf(':', colon + 1) >= 0) {
            return null;
        }
        try {
            return Long.parseLong(ref.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 목차 항목 열쇠. TOC_ENTRY가 아니거나 형식이 다르면 null. */
    public TocKey tocKey() {
        if (kind != ObjectKind.TOC_ENTRY) {
            return null;
        }
        String[] p = ref.split(":", -1);
        if (p.length != 4 || !"t".equals(p[0]) || !p[2].matches("[0-9a-f]{64}")) {
            return null;
        }
        try {
            return new TocKey(Long.parseLong(p[1]), p[2], Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 범위를 뺀 같은 원천인가(새 참조/기존 참조 판단용). */
    public String identity() {
        return kind + "|" + ref;
    }

    public record TocKey(long bookRefId, String keyHash, int keyLine) {
    }
}
