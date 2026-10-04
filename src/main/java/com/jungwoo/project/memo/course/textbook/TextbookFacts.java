package com.jungwoo.project.memo.course.textbook;

/**
 * 상담·계획 입력에 싣는 "지금 쓰는 교재" 한 줄. 화면이 보이는 교재와 생성기가 읽는 교재가 같게, 교재 칸(사용자 정정 포함)을 그대로
 * 옮긴다. 어떻게 정해졌는지(직접 적음·자료·웹)를 함께 적어, 이미 정해진 교재를 다시 묻지 않게 한다.
 */
public final class TextbookFacts {

    private TextbookFacts() {
    }

    /** 「제목」 판 · 출판사 · 저자 — 출처. 제목·ISBN이 모두 없으면 null. */
    public static String identity(String title, String isbn, String edition, String publisher, String author,
                                  String source) {
        if (blank(title) && blank(isbn)) {
            return null;
        }
        StringBuilder sb = new StringBuilder("「").append(blank(title) ? "ISBN " + isbn : title.trim()).append("」");
        if (!blank(edition)) {
            sb.append(' ').append(edition.trim());
        }
        if (!blank(publisher)) {
            sb.append(" · ").append(publisher.trim());
        }
        if (!blank(author)) {
            sb.append(" · ").append(author.trim());
        }
        String how = switch (source == null ? "" : source) {
            case "USER" -> "사용자가 정함";
            case "MATERIAL" -> "자료에서 확인";
            case "WEB" -> "웹에서 판을 확인";
            default -> null;
        };
        if (how != null) {
            sb.append(" — ").append(how);
        }
        return sb.toString();
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
