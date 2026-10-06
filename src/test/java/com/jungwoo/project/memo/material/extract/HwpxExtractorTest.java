package com.jungwoo.project.memo.material.extract;

import com.jungwoo.project.memo.material.domain.TextUnitType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HWPX(OWPML) 추출. 합성 파일이지만 파싱은 실제 hwpxlib가 한다 — 파서를 목으로 바꾸지 않는다.
 *
 * <p>여기서 지키려는 것 셋: 표의 행·셀 관계가 남는가, 외부 엔티티 선언이 있는 파일을 파서에 넘기지
 * 않는가, section 번호를 쪽수라고 부르지 않는가.
 */
class HwpxExtractorTest {

    private final HwpxExtractor extractor = new HwpxExtractor();

    @Test
    void 본문과_표를_문서_순서대로_읽는다(@TempDir Path dir) throws Exception {
        Path file = HwpxFixture.create()
                .paragraph("자료구조 강의계획서")
                .paragraph("3주차 연결 리스트")
                .table(List.of(List.of("주차", "내용", "제출일"),
                        List.of("3주차", "연결 리스트", "9월 25일"),
                        List.of("5주차", "스택", "10월 9일")))
                .write(dir.resolve("syllabus.hwpx"));

        ExtractedDocument document = extractor.extract(file);

        assertThat(document.fullText()).contains("자료구조 강의계획서").contains("3주차 연결 리스트");
        // 표는 행마다 한 줄, 셀은 |로 나뉜다. 이게 없으면 "9월 25일"이 어느 주차의 값인지 알 수 없다.
        assertThat(document.fullText())
                .contains("[표 시작]")
                .contains("| 주차 | 내용 | 제출일 |")
                .contains("| 3주차 | 연결 리스트 | 9월 25일 |")
                .contains("| 5주차 | 스택 | 10월 9일 |")
                .contains("[표 끝]");
        // 쪽수는 파일에 없다. section 번호를 쪽수로 쓰지 않는다.
        assertThat(document.pageCount()).isNull();
        assertThat(document.units()).extracting(ExtractedDocument.ExtractedUnit::type)
                .containsOnly(TextUnitType.TEXT_BLOCK);
        assertThat(document.units()).extracting(ExtractedDocument.ExtractedUnit::unitNo).containsExactly(1);
    }

    @Test
    void 외부_엔티티_선언이_있으면_파서에_넘기지_않는다(@TempDir Path dir) throws Exception {
        Path file = HwpxFixture.create().paragraph("본문").withDoctype().write(dir.resolve("xxe.hwpx"));

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("외부 엔티티");
    }

    @Test
    void mimetype이_없으면_HWPX가_아니다(@TempDir Path dir) throws Exception {
        Path file = HwpxFixture.create().paragraph("본문").withoutMimetype().write(dir.resolve("plain.hwpx"));

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(DocumentExtractionException.class)
                .extracting(e -> ((DocumentExtractionException) e).reason())
                .isEqualTo(DocumentExtractionException.Reason.CORRUPTED);
    }

    @Test
    void 확장자만_HWPX인_파일은_읽지_않는다(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("fake.hwpx");
        Files.writeString(file, "이건 그냥 텍스트다");

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(DocumentExtractionException.class);
    }

    @Test
    void 해제_크기가_상한을_넘으면_거부한다(@TempDir Path dir) throws Exception {
        // 0으로 채운 항목이라 압축은 작지만 풀면 상한을 넘는다 — 헤더 크기가 아니라 실제로 읽으며 센다.
        Path file = HwpxFixture.create().paragraph("본문")
                .padding((int) ExtractionLimits.MAX_CONTAINER_ENTRY_BYTES + 1024 * 1024)
                .write(dir.resolve("bomb.hwpx"));

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(DocumentExtractionException.class)
                .extracting(e -> ((DocumentExtractionException) e).reason())
                .isEqualTo(DocumentExtractionException.Reason.TOO_LARGE);
    }
}
