package com.jungwoo.project.memo.material.extract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Jupyter 노트북(.ipynb)에서 셀 원문을 읽는다. 정식 지원 범위는 nbformat 4 계열이다.
 *
 * <p>규칙:
 * <ul>
 *   <li>셀 번호는 원본 순서의 1-based 번호다. 빈 셀을 건너뛰어도 번호는 밀리지 않는다.
 *       execution_count는 위치가 아니다 — 실행 순서라 비어 있거나 중복될 수 있다.</li>
 *   <li>단위는 셀 하나다({@link TextUnitType#NOTEBOOK_CELL}). 그래서 구간이 "셀 8~12"로 말할 수 있다.
 *       긴 셀은 나누되 같은 셀 번호를 유지한다.</li>
 *   <li>코드 셀은 ``` 울타리 안에 원문 그대로 둔다 — 개행·들여쓰기가 코드의 의미다.</li>
 *   <li>출력은 텍스트만 상한 안에서 보조로 싣는다. 이미지·HTML·JavaScript는 넣지 않고 종류만 적는다.
 *       코드를 실행하지도, HTML을 렌더링하지도 않는다. 원문의 지시문은 데이터일 뿐이다.</li>
 * </ul>
 */
@Component
public class NotebookExtractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 마크다운에 붙여 넣은 그림. Jupyter는 화면 캡처를 붙여 넣으면 {@code ![image.png](data:image/png;base64,...)}로
     * 셀 원문에 통째로 넣는다. 수업 노트북에 흔하고 한 장이 수십만 자다 — 글자로 읽으면 본문이 base64로 덮이고
     * 저장 한도(DB 패킷)도 넘긴다. 그림 자리만 남긴다.
     */
    private static final Pattern MARKDOWN_DATA_IMAGE =
            Pattern.compile("!\\[([^\\]]*)]\\(\\s*data:[\\w.+/-]+;base64,[A-Za-z0-9+/=\\s]*\\)");
    /** HTML 태그나 다른 자리에 들어간 data URI. */
    private static final Pattern DATA_URI = Pattern.compile("data:[\\w.+/-]+;base64,[A-Za-z0-9+/=]{64,}");

    public ExtractedDocument extract(byte[] bytes) {
        JsonNode root = read(bytes);
        if (!root.isObject()) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "노트북 파일이 아니에요 (JSON 객체가 아닙니다)");
        }
        JsonNode format = root.get("nbformat");
        if (format == null || !format.isInt()) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "노트북 파일이 아니에요 (nbformat이 없습니다)");
        }
        if (format.intValue() != 4) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.UNSUPPORTED_VERSION,
                    "nbformat " + format.intValue() + " 노트북은 지원하지 않아요 (nbformat 4로 저장해 주세요)");
        }
        JsonNode cells = root.get("cells");
        if (cells == null || !cells.isArray()) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "노트북에 cells 목록이 없어요");
        }

        String language = language(root);
        ExtractedDocument.Builder builder = ExtractedDocument.builder();
        int cellNo = 0;
        for (JsonNode cell : cells) {
            cellNo++;
            if (builder.textLength() >= ExtractionLimits.MAX_DOCUMENT_CHARS
                    || builder.isEmpty() && cellNo > ExtractionLimits.MAX_UNITS) {
                builder.warn(ExtractionLimits.truncatedWarning("노트북 분량"));
                break;
            }
            String type = cell.path("cell_type").asText("");
            String source = stripEmbeddedData(
                    joinSource(cell.has("source") ? cell.get("source") : cell.path("input")));
            String body = body(type, source, language) + outputs(cell, builder);
            if (body.isBlank()) {
                continue; // 빈 셀. 번호는 이미 올렸으므로 뒤 셀의 번호가 밀리지 않는다.
            }
            appendCell(builder, cellNo, type, body);
        }
        return builder.build();
    }

    private JsonNode read(byte[] bytes) {
        try {
            return JSON.readTree(bytes);
        } catch (JsonProcessingException e) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "노트북 JSON을 읽지 못했어요", e);
        } catch (IOException e) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "노트북 파일을 읽지 못했어요", e);
        }
    }

    /** 긴 셀은 나눈다. 셀 번호는 그대로라 위치가 "셀 12"로 남는다. */
    private void appendCell(ExtractedDocument.Builder builder, int cellNo, String type, String body) {
        String kind = kindLabel(type);
        for (int pos = 0; pos < body.length(); pos += ExtractionLimits.MAX_UNIT_CHARS) {
            String part = body.substring(pos, Math.min(body.length(), pos + ExtractionLimits.MAX_UNIT_CHARS));
            String heading = "--- 셀 " + cellNo + " · " + kind
                    + (pos > 0 ? " (이어서)" : "") + " ---";
            builder.unit(TextUnitType.NOTEBOOK_CELL, cellNo, heading, part);
        }
    }

    private static String kindLabel(String type) {
        return switch (type) {
            case "code" -> "코드";
            case "markdown" -> "설명";
            default -> "원본";
        };
    }

    /** 셀 원문에 박힌 base64 그림을 "[그림: 이름]"으로 바꾼다. 그림이 있었다는 사실은 남긴다. */
    static String stripEmbeddedData(String source) {
        if (source.indexOf("base64,") < 0) {
            return source;
        }
        String withoutMarkdownImages = MARKDOWN_DATA_IMAGE.matcher(source).replaceAll(match -> {
            String alt = match.group(1).isBlank() ? "그림" : "그림: " + match.group(1).strip();
            return java.util.regex.Matcher.quoteReplacement("[" + alt + "]");
        });
        return DATA_URI.matcher(withoutMarkdownImages).replaceAll("[그림 데이터 생략]");
    }

    private static String body(String type, String source, String language) {
        if (source.isBlank()) {
            return "";
        }
        if ("code".equals(type)) {
            return "```" + language + "\n" + source.stripTrailing() + "\n```\n";
        }
        return source.stripTrailing() + "\n";
    }

    /**
     * 실행 결과. 텍스트만, 셀마다 몇 개까지만 싣는다. 나머지는 종류만 적는다 — 그래야 "그림으로
     * 답이 나와 있는 셀"을 사람이 알아볼 수 있다.
     */
    private static String outputs(JsonNode cell, ExtractedDocument.Builder builder) {
        JsonNode outputs = cell.path("outputs");
        if (!outputs.isArray() || outputs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        int skipped = 0;
        for (JsonNode output : outputs) {
            if (taken >= ExtractionLimits.MAX_OUTPUTS_PER_CELL) {
                skipped++;
                continue;
            }
            String text = outputText(output);
            if (text == null || text.isBlank()) {
                skipped++;
                continue;
            }
            boolean cut = text.length() > ExtractionLimits.MAX_OUTPUT_CHARS;
            if (cut) {
                text = text.substring(0, ExtractionLimits.MAX_OUTPUT_CHARS);
                builder.warn("실행 결과가 길어 일부만 읽었어요");
            }
            sb.append("[실행 결과").append(cut ? " · 일부" : "").append("]\n")
                    .append(text.stripTrailing()).append('\n');
            taken++;
        }
        if (skipped > 0) {
            // 이미지·HTML 등. base64를 본문에 넣지 않는다.
            sb.append("[실행 결과 ").append(skipped).append("개는 텍스트가 아니라 싣지 않았어요]\n");
        }
        return sb.toString();
    }

    private static String outputText(JsonNode output) {
        String type = output.path("output_type").asText("");
        if ("stream".equals(type)) {
            return joinSource(output.path("text"));
        }
        if ("error".equals(type)) {
            String name = output.path("ename").asText("");
            String value = output.path("evalue").asText("");
            return (name + " " + value).isBlank() ? null : name + ": " + value;
        }
        if ("execute_result".equals(type) || "display_data".equals(type)) {
            JsonNode plain = output.path("data").path("text/plain");
            return plain.isMissingNode() ? null : joinSource(plain);
        }
        return null;
    }

    /** source·text는 문자열일 수도, 줄 단위 배열일 수도 있다(nbformat 4 양쪽 다 허용). */
    private static String joinSource(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            node.forEach(line -> sb.append(line.asText("")));
            return sb.toString();
        }
        return node.isTextual() ? node.asText() : "";
    }

    private static String language(JsonNode root) {
        JsonNode metadata = root.path("metadata");
        String kernel = metadata.path("kernelspec").path("language").asText("");
        if (!kernel.isBlank()) {
            return kernel;
        }
        return metadata.path("language_info").path("name").asText("");
    }
}
