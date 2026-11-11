package com.jungwoo.project.memo.course.textbook.web;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 모델 보조에 보낼 교재 칸 주변 줄. 교재 표 머리(도서명·저자·출판사 중 둘 이상) 앞 3줄 ~ 뒤 12줄만 — 강의계획서 전체를 보내지 않는다.
 */
final class ClueWindow {

    private static final Pattern TITLE = Pattern.compile("(도서명|교재명|서\\s?명|책\\s?제목|Title)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHOR = Pattern.compile("(저\\s?자|지은이|Author)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PUBLISHER = Pattern.compile("(출판사|발행처|Publisher)", Pattern.CASE_INSENSITIVE);

    private ClueWindow() {
    }

    static List<String> around(List<TextbookExtractor.Unit> units) {
        for (TextbookExtractor.Unit unit : units) {
            if (unit.text() == null) {
                continue;
            }
            String[] lines = unit.text().split("\\R");
            for (int i = 0; i < lines.length; i++) {
                if (isHeader(lines[i])) {
                    List<String> out = new ArrayList<>();
                    for (int j = Math.max(0, i - 3); j < Math.min(lines.length, i + 13); j++) {
                        out.add(lines[j]);
                    }
                    return out;
                }
            }
        }
        return List.of();
    }

    static int unitOf(List<TextbookExtractor.Unit> units) {
        for (TextbookExtractor.Unit unit : units) {
            if (unit.text() != null) {
                for (String line : unit.text().split("\\R")) {
                    if (isHeader(line)) {
                        return unit.unitNo();
                    }
                }
            }
        }
        return 1;
    }

    private static boolean isHeader(String line) {
        if (line == null || line.length() > 80) {
            return false;
        }
        int hits = (TITLE.matcher(line).find() ? 1 : 0) + (AUTHOR.matcher(line).find() ? 1 : 0)
                + (PUBLISHER.matcher(line).find() ? 1 : 0);
        return hits >= 2;
    }
}
