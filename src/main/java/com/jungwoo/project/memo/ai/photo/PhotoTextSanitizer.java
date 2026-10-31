package com.jungwoo.project.memo.ai.photo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사진 읽기 결과를 저장 전에 정리한다(모든 저장 필드). 모델 지시("개인 정보 칸은 옮기지 않는다")의 보완이지 보장이 아니다 —
 * 손글씨 이름처럼 꼴이 없는 개인 정보는 여기서 잡지 못한다. 원본 사진은 30일 뒤 지우고, 이 글은 사용자가 지울 수 있다.
 */
public final class PhotoTextSanitizer {

    public static final int MAX_TEXT = 12_000;
    public static final int MAX_HANDWRITING = 1_500;
    public static final int MAX_HEADINGS = 5;
    public static final int MAX_HEADING = 120;
    public static final String REDACTED = "[개인정보 생략]";

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 휴대폰·지역 번호(하이픈·점·공백 구분 또는 붙여 씀). */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)0\\d{1,2}[-. ]?\\d{3,4}[-. ]?\\d{4}(?!\\d)");
    /** 주민등록번호 꼴. */
    private static final Pattern RRN = Pattern.compile("(?<!\\d)\\d{6}[- ]?[1-8]\\d{6}(?!\\d)");
    private static final Pattern PAGE = Pattern.compile("^\\D{0,3}(\\d{1,4})\\D{0,3}$");

    /**
     * @param truncated 길이 상한으로 글을 잘랐다(화면·로그 안내용)
     */
    public record Clean(Integer printedPage, List<String> headings, String text, String handwriting, boolean truncated) {

        /** 본문 단위에 넣을 글: 인쇄된 글 + 손글씨(작성자 미확인 표지와 함께). */
        public String unitText() {
            if (handwriting == null || handwriting.isBlank()) {
                return text;
            }
            return text + "\n[손글씨 — 누가 썼는지 확인되지 않음]\n" + handwriting;
        }
    }

    private PhotoTextSanitizer() {
    }

    public static Clean clean(TextbookPhotoReader.Raw raw) {
        boolean[] cut = {false};
        String text = limit(scrub(raw.text()), MAX_TEXT, cut);
        String hand = raw.handwriting() == null ? null : limit(scrub(raw.handwriting()), MAX_HANDWRITING, cut);
        if (hand != null && hand.isBlank()) {
            hand = null;
        }
        List<String> headings = new ArrayList<>();
        for (String h : raw.headings() == null ? List.<String>of() : raw.headings()) {
            String one = limit(scrub(h).replace('\n', ' ').strip(), MAX_HEADING, cut);
            if (!one.isBlank() && headings.size() < MAX_HEADINGS && !headings.contains(one)) {
                headings.add(one);
            }
        }
        return new Clean(page(raw.printedPage()), List.copyOf(headings), text, hand, cut[0]);
    }

    static String scrub(String s) {
        if (s == null) {
            return "";
        }
        String out = CONTROL.matcher(s.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
        out = EMAIL.matcher(out).replaceAll(Matcher.quoteReplacement(REDACTED));
        out = RRN.matcher(out).replaceAll(Matcher.quoteReplacement(REDACTED));
        out = PHONE.matcher(out).replaceAll(Matcher.quoteReplacement(REDACTED));
        return out.strip();
    }

    static Integer page(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = PAGE.matcher(raw.strip());
        if (!m.matches()) {
            return null;
        }
        int n = Integer.parseInt(m.group(1));
        return n <= 0 ? null : n;
    }

    private static String limit(String s, int max, boolean[] cut) {
        if (s.length() <= max) {
            return s;
        }
        cut[0] = true;
        return s.substring(0, max);
    }
}
