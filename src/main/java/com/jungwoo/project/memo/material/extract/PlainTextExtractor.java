package com.jungwoo.project.memo.material.extract;

import com.jungwoo.project.memo.material.domain.TextUnitType;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 셸 스크립트(.sh) 같은 평문 자료를 <b>읽기만</b> 한다.
 *
 * <p>★ 아무것도 실행하지 않는다. 셸을 띄우지 않고, 파일 안의 명령·경로·import를 해석하거나 따라가지 않는다 — 바이트를 글자로
 * 풀어 줄 단위 블록으로 나눌 뿐이다. 실습 자료에 딸린 스크립트를 "무엇을 하는 스크립트인지" 상담·계획이 읽을 수 있게 하는 것이
 * 목적이고, 문서 안의 지시문은 다른 자료와 마찬가지로 모델 프롬프트에서 데이터로 취급된다.
 *
 * <p>평문에는 시그니처가 없어서 확장자만 바꾼 바이너리를 거를 방법이 NUL 바이트와 디코딩 실패뿐이다. UTF-8로 읽히지 않으면
 * 한국 윈도우 편집기의 기본인 MS949로 한 번 더 읽고, 그것도 안 되면 손상된 파일로 본다.
 */
public class PlainTextExtractor {

    /** 블록 하나에 담는 줄 수. 위치 표시는 "블록 N"이고 머리글에 줄 범위를 적는다. */
    static final int LINES_PER_BLOCK = 60;

    public ExtractedDocument extract(byte[] bytes) {
        if (bytes.length == 0) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED, "빈 파일이에요");
        }
        for (byte b : bytes) {
            if (b == 0) {
                throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                        "텍스트 파일이 아니에요(바이너리 내용이 들어 있어요)");
            }
        }
        String text = decode(bytes);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');

        ExtractedDocument.Builder builder = ExtractedDocument.builder();
        if (text.length() > ExtractionLimits.MAX_DOCUMENT_CHARS) {
            text = text.substring(0, ExtractionLimits.MAX_DOCUMENT_CHARS);
            builder.warn("파일이 길어 앞부분 " + ExtractionLimits.MAX_DOCUMENT_CHARS + "자까지만 읽었어요");
        }
        // 파일 끝의 개행은 줄이 아니다 — 그대로 나누면 빈 줄이 하나 더 생긴다.
        String[] lines = (text.endsWith("\n") ? text.substring(0, text.length() - 1) : text).split("\n", -1);
        int unitNo = 1;
        for (int from = 0; from < lines.length && unitNo <= ExtractionLimits.MAX_UNITS; from += LINES_PER_BLOCK) {
            int to = Math.min(lines.length, from + LINES_PER_BLOCK);
            StringBuilder block = new StringBuilder();
            for (int i = from; i < to; i++) {
                block.append(lines[i]).append('\n');
            }
            String body = block.toString();
            if (body.isBlank()) {
                continue;
            }
            if (body.length() > ExtractionLimits.MAX_UNIT_CHARS) {
                body = body.substring(0, ExtractionLimits.MAX_UNIT_CHARS);
                builder.warn("아주 긴 줄이 있어 일부를 잘랐어요");
            }
            builder.unit(TextUnitType.TEXT_BLOCK, unitNo, "[블록 " + unitNo + " · " + (from + 1) + "~" + to + "행]", body);
            unitNo++;
        }
        ExtractedDocument document = builder.build();
        if (!document.hasText()) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED, "읽을 글자가 없어요");
        }
        return document;
    }

    private static String decode(byte[] bytes) {
        try {
            return strict(StandardCharsets.UTF_8, bytes);
        } catch (CharacterCodingException notUtf8) {
            try {
                return strict(Charset.forName("MS949"), bytes);
            } catch (Exception notKorean) {
                throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                        "글자 인코딩을 알 수 없어 읽지 못했어요(UTF-8로 저장해 다시 올려 주세요)");
            }
        }
    }

    private static String strict(Charset charset, byte[] bytes) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
