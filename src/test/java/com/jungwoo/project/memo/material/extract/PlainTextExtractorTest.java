package com.jungwoo.project.memo.material.extract;

import com.jungwoo.project.memo.material.MaterialFileFormat;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** handoff T15 — .sh·C++ 소스는 읽기만 한다. 실행·컴파일하지 않는다. */
class PlainTextExtractorTest {

    private final PlainTextExtractor extractor = new PlainTextExtractor();

    @Test
    void 스크립트를_실행하지_않고_글자_그대로_줄_블록으로_읽는다(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("executed.txt");
        String script = "#!/bin/bash\n# EC2 초기 설정\ntouch '" + marker.toString().replace('\\', '/') + "'\nsudo apt update\n";

        ExtractedDocument document = extractor.extract(script.getBytes(StandardCharsets.UTF_8));

        assertThat(document.units()).singleElement().satisfies(u -> {
            assertThat(u.type()).isEqualTo(TextUnitType.TEXT_BLOCK);
            assertThat(u.text()).contains("sudo apt update").contains("touch");
        });
        assertThat(document.fullText()).contains("[블록 1 · 1~");
        // 읽는 동안 파일 안의 명령이 수행되지 않았다.
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void 긴_스크립트는_60줄_블록으로_나뉘고_위치를_머리글에_남긴다() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 130; i++) {
            sb.append("echo line-").append(i).append('\n');
        }

        ExtractedDocument document = extractor.extract(sb.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(document.units()).hasSize(3);
        assertThat(document.units().get(1).text()).startsWith("echo line-61\n");
        assertThat(document.fullText()).contains("[블록 2 · 61~120행]");
    }

    @Test
    void 한국어_윈도우_인코딩과_BOM과_CRLF를_읽는다() {
        byte[] ms949 = "# 서버 설정\r\necho 시작\r\n".getBytes(Charset.forName("MS949"));
        assertThat(extractor.extract(ms949).fullText()).contains("# 서버 설정\necho 시작");

        byte[] bom = ("﻿" + "echo hi\n").getBytes(StandardCharsets.UTF_8);
        assertThat(extractor.extract(bom).units().get(0).text()).isEqualTo("echo hi\n");
    }

    @Test
    void 확장자만_sh인_바이너리는_읽지_않는다() {
        byte[] binary = {0x7F, 'E', 'L', 'F', 0, 0, 1, 2};
        assertThatThrownBy(() -> extractor.extract(binary)).isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("텍스트 파일이 아니에요");
        assertThat(MaterialFileFormat.SH.accepts(binary)).isFalse();
        assertThat(MaterialFileFormat.SH.accepts("PKzip".getBytes(StandardCharsets.ISO_8859_1))).isFalse();
        assertThat(MaterialFileFormat.SH.accepts("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(MaterialFileFormat.fromFilename("setup.SH")).contains(MaterialFileFormat.SH);
        assertThat(DocumentExtractionService.handles("sh")).isTrue();
    }

    @Test
    void C_PLUS_PLUS_소스와_헤더도_텍스트_자료로_받아_줄_블록으로_읽는다() {
        String source = "#include <iostream>\n// 3주차 실습: 포인터\nint main() {\n    std::cout << \"hi\";\n}\n";

        ExtractedDocument document = extractor.extract(source.getBytes(StandardCharsets.UTF_8));

        assertThat(document.units()).singleElement().satisfies(u -> {
            assertThat(u.type()).isEqualTo(TextUnitType.TEXT_BLOCK);
            assertThat(u.text()).contains("#include <iostream>").contains("std::cout");
        });
        assertThat(MaterialFileFormat.fromFilename("3주차/main.CPP")).contains(MaterialFileFormat.CPP);
        assertThat(MaterialFileFormat.fromFilename("stack.cc")).contains(MaterialFileFormat.CC);
        assertThat(MaterialFileFormat.fromFilename("stack.cxx")).contains(MaterialFileFormat.CXX);
        assertThat(MaterialFileFormat.fromFilename("stack.h")).contains(MaterialFileFormat.H);
        assertThat(MaterialFileFormat.fromFilename("stack.hpp")).contains(MaterialFileFormat.HPP);
        assertThat(MaterialFileFormat.CPP.contentType()).isEqualTo("text/plain");
        assertThat(MaterialFileFormat.CPP.accepts(source.getBytes(StandardCharsets.UTF_8))).isTrue();
        // 컴파일된 결과물은 확장자만 바꿔도 받지 않는다.
        assertThat(MaterialFileFormat.CPP.accepts(new byte[]{'M', 'Z', (byte) 0x90, 0})).isFalse();
        for (String ext : new String[]{"cpp", "cc", "cxx", "h", "hpp"}) {
            assertThat(DocumentExtractionService.handles(ext)).as(ext).isTrue();
        }
        // C++까지 열었다고 다른 소스 형식까지 연 것은 아니다.
        assertThat(MaterialFileFormat.fromFilename("main.py")).isEmpty();
        assertThat(MaterialFileFormat.fromFilename("main.o")).isEmpty();
    }

    @Test
    void 빈_파일은_이유와_함께_실패한다() {
        assertThatThrownBy(() -> extractor.extract(new byte[0])).isInstanceOf(DocumentExtractionException.class);
        assertThatThrownBy(() -> extractor.extract("\n\n  \n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentExtractionException.class);
    }
}
