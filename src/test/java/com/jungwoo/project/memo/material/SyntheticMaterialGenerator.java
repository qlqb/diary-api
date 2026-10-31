package com.jungwoo.project.memo.material;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실호출 검증용 합성 자료를 build/synthetic 에 만든다. 사용자 실제 파일을 쓰지 않기 위한 도구다.
 *
 * <p>SYNTHETIC_MATERIALS=1 일 때만 돈다. 세 종류:
 * <ol>
 *   <li>problem-free.pdf — 설명만 있고 문제·제출이 없는 짧은 자료(3쪽)</li>
 *   <li>long-late-exercise.pdf — 36쪽. 앞 35쪽은 설명, 마지막 쪽에만 실습 문제와 제출 요구가 있다</li>
 *   <li>ambiguous-due.pdf — 과제 안내. "다음 수업까지", "9월 18일"(연도 없음), "2026년 9월 25일까지"가 섞여 있다</li>
 * </ol>
 * 한글은 Windows의 맑은 고딕(malgun.ttf)으로 그린다. 폰트가 없으면 영문으로 대체한다.
 */
@EnabledIfEnvironmentVariable(named = "SYNTHETIC_MATERIALS", matches = "1")
class SyntheticMaterialGenerator {

    private static final Path OUT = Path.of("build", "synthetic");

    @Test
    void generate() throws Exception {
        Files.createDirectories(OUT);
        writePdf(OUT.resolve("problem-free.pdf"), problemFree());
        writePdf(OUT.resolve("long-late-exercise.pdf"), longLateExercise());
        writePdf(OUT.resolve("ambiguous-due.pdf"), ambiguousDue());
        assertThat(OUT.resolve("long-late-exercise.pdf")).exists();
        System.out.println("SYNTHETIC OUT = " + OUT.toAbsolutePath());
    }

    private static List<List<String>> problemFree() {
        return List.of(
                List.of("2주차 · 연결 리스트 개요", "연결 리스트는 노드가 포인터로 이어진 선형 자료구조다.",
                        "배열과 달리 크기가 고정되지 않고, 삽입·삭제가 포인터 조작만으로 이루어진다.",
                        "이 자료는 개념 설명만 담고 있으며 문제나 제출 요구가 없다."),
                List.of("단순 연결 리스트의 구조", "각 노드는 데이터 필드와 다음 노드를 가리키는 링크 필드로 구성된다.",
                        "머리 포인터(head)가 첫 노드를 가리키고 마지막 노드의 링크는 NULL이다."),
                List.of("탐색의 시간 복잡도", "특정 값을 찾으려면 머리부터 순서대로 따라가야 하므로 O(n)이다.",
                        "다음 시간에는 삽입과 삭제를 다룬다."));
    }

    private static List<List<String>> longLateExercise() {
        java.util.ArrayList<List<String>> pages = new java.util.ArrayList<>();
        String[] topics = {"연결 리스트 삽입", "연결 리스트 삭제", "이중 연결 리스트", "원형 연결 리스트", "스택의 개념",
                "스택 배열 구현", "큐의 개념", "원형 큐"};
        for (int p = 1; p <= 35; p++) {
            String topic = topics[(p - 1) % topics.length];
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            lines.add((p + 2) + "주차 · " + topic + " (" + p + ")");
            for (int l = 0; l < 22; l++) {
                lines.add(topic + "에 대한 설명 문장 " + (l + 1) + ". 노드와 포인터, 경계 조건, 시간 복잡도를 차례로 살펴본다.");
            }
            pages.add(lines);
        }
        pages.add(List.of("실습 3 · 연결 리스트 삭제 구현",
                "문제 1. 머리 노드를 삭제하는 함수 delete_head(list)를 구현하라.",
                "문제 2. 중간 노드(값이 k인 첫 노드)를 삭제하는 함수 delete_value(list, k)를 구현하라.",
                "문제 3. 빈 리스트와 노드가 하나뿐인 리스트에서 위 두 함수가 올바르게 동작하는지 확인하라.",
                "제출: 실습 결과 화면과 코드를 LMS에 제출하세요. 마감은 2026년 9월 18일 23:59입니다.",
                "채점 기준: 경계 조건 처리 40%, 메모리 해제 30%, 코드 설명 30%"));
        return pages;
    }

    private static List<List<String>> ambiguousDue() {
        return List.of(
                List.of("스마트앱프로젝트 · 과제 안내", "과제 A: 로그인 화면 프로토타입을 만들어 제출하세요.",
                        "제출 기한: 다음 수업까지.", "제출 방법: LMS 과제함."),
                List.of("과제 B: 레이아웃 실습 결과 보고서", "9월 18일까지 제출. 분량은 2쪽 이내.",
                        "참고: 지난 학기 자료의 날짜는 2025년 기준이었으나 이번 학기 일정은 강의계획서를 따른다."),
                List.of("과제 C: 이벤트 처리 실습", "2026년 9월 25일까지 제출합니다.",
                        "연습 문제: 버튼 클릭 횟수를 세는 앱을 만들어 보라. (제출 없음, 연습용)"));
    }

    private static void writePdf(Path file, List<List<String>> pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            for (List<String> lines : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.setFont(font, 11);
                    cs.beginText();
                    cs.setLeading(15f);
                    cs.newLineAtOffset(48, 740);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                document.save(out);
            }
        }
    }

    private static PDFont loadFont(PDDocument document) throws Exception {
        for (String candidate : List.of("C:/Windows/Fonts/malgun.ttf", "C:/Windows/Fonts/NanumGothic.ttf")) {
            File f = new File(candidate);
            if (f.exists()) {
                return PDType0Font.load(document, f);
            }
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }
}
