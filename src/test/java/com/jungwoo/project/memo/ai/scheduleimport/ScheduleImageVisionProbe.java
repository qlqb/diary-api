package com.jungwoo.project.memo.ai.scheduleimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.scheduleimport.dto.RawScheduleTable;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse.RowView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 실제 모델에 근무표 이미지를 보내 <b>받아쓰기가 받아쓰기로 남는지</b>를 눈으로 확인하는
 * 수동 프로브. 단정문이 없다 — 확인할 것은 "값이 맞는가"가 아니라 "모델이 해석을 시작하지
 * 않는가"이고, 그건 원문을 사람이 읽어야 안다.
 *
 * <p><b>{@code SCHEDULE_IMPORT_PROBE=1}일 때만 돈다.</b> 평소 {@code ./gradlew test}에
 * 섞이면 안 되는 이유가 셋이다. (1) 확률적인 외부 호출이 스위트 안에 있으면 무관한 변경이
 * 빨갛게 뜬다 — 오늘 3/3이어도 언젠가 이름 한 글자가 흔들린다. (2) 이 저장소는 공개라
 * API 키 없이 클론한 사람에게는 그냥 실패로 보인다. (3) 호출마다 돈이 나간다.
 *
 * <p>결정적 검증은 늘 돌고(픽스처), 확률적 검증은 명시적으로만 돈다. 그 분리가 이 기능의
 * 검증 구조다.
 *
 * <p>이미지가 없어도 건너뛴다. 이미지는 저장소에 넣지 않는다 — 동료들의 실명과 근무 시간이
 * 들어 있다. 경로는 {@code SCHEDULE_IMPORT_IMAGE}로 저장소 밖을 가리킬 수 있다.
 *
 * <p>3회 돌리는 이유는 1회로는 흔들림을 볼 수 없기 때문이다. 같은 이미지에서 같은 셀이
 * 매번 다르게 나오면 그건 프롬프트가 아직 덜 조여진 것이다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SCHEDULE_IMPORT_PROBE", matches = "1",
        disabledReason = "실제 모델을 호출하는 관측용 프로브. SCHEDULE_IMPORT_PROBE=1일 때만 돈다")
@EnabledIf("imageExists")
class ScheduleImageVisionProbe {

    /**
     * 이미지를 둘 곳. {@code SCHEDULE_IMPORT_IMAGE}로 저장소 밖 경로를 줄 수 있다 —
     * 근무표에는 동료 실명과 근무 시간이 있어서 저장소 안에 두지 않는 편이 낫다.
     */
    private static final Path DIR = Path.of(
            System.getenv().getOrDefault("SCHEDULE_IMPORT_IMAGE", "src/test/resources/schedule-import"));

    private static final int RUNS = 3;

    /**
     * 이름을 고정하지 않는다. 이 폴더에 놓인 첫 이미지를 쓴다 — 사진은 대개 카카오톡이나
     * 카메라가 붙인 이름 그대로 들어오고, 그때마다 이름을 바꾸게 하면 안 바꾼 채로 돌려
     * "건너뛰었다"는 결과만 보게 된다.
     */
    static Path image() {
        if (!Files.isDirectory(DIR)) {
            return null;
        }
        try (Stream<Path> files = Files.list(DIR)) {
            return files.filter(Files::isRegularFile)
                    .filter(ScheduleImageVisionProbe::isImage)
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    static boolean imageExists() {
        return image() != null;
    }

    @Autowired
    private ScheduleImageVisionClient visionClient;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("같은 이미지를 3회 받아쓰게 하고 원문과 해석 결과를 남긴다")
    void transcribesTheSameImageThreeTimes() throws Exception {
        Path source = image();
        byte[] image = Files.readAllBytes(source);
        String contentType = source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")
                ? "image/png" : "image/jpeg";
        System.out.println("이미지: " + source + " (" + image.length + " bytes, " + contentType + ")");
        Path out = Path.of("build/schedule-import-probe");
        Files.createDirectories(out);

        for (int run = 1; run <= RUNS; run++) {
            long startedAt = System.currentTimeMillis();
            RawScheduleTable raw = visionClient.read(image, contentType);
            long elapsed = System.currentTimeMillis() - startedAt;

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw);
            Files.writeString(out.resolve("run-" + run + ".json"), json);
            System.out.println("===== RUN " + run + " (" + elapsed + "ms) =====");
            System.out.println(json);

            /*
             * 원문만 봐서는 통과 여부를 알 수 없다. 3회 다 "잘 받아썼다"로 보여도 머리글이
             * 셀 수와 어긋나면 후보는 0건이다 — 실제로 그렇게 두 번 죽었다. 그래서 합격
             * 판정은 해석기의 최종 결과로 한다.
             */
            ScheduleExtractionResponse result =
                    ScheduleTableInterpreter.interpret(raw, LocalDate.now(), null);
            long invalid = result.rows().stream().filter(RowView::rowInvalid).count();
            System.out.println("----- RUN " + run + " 해석 -----"
                    + " scheduleColumns=" + raw.safeScheduleColumns().size()
                    + " | columnsNormalized=" + result.columnsNormalized()
                    + " | columnsUnrecognized=" + result.columnsUnrecognized()
                    + " | rowInvalid=" + invalid + "/" + result.rows().size()
                    + " | periodWeekdayMismatch=" + result.periodWeekdayMismatch()
                    + " | unresolved=" + result.unresolvedCodes()
                    + " | period=" + (result.resolvedPeriod() == null ? "null"
                        : result.resolvedPeriod().startDate() + "~" + result.resolvedPeriod().endDate()));
            for (RowView row : result.rows()) {
                System.out.println("    [" + row.index() + "] " + row.name() + "/" + row.tag() + " "
                        + (row.rowInvalid() ? "행 무효" : row.cells().stream()
                            .map(c -> c.date() + ":" + c.kind()
                                + (c.start() == null ? "" : "(" + c.start() + "-" + c.end()
                                + (c.crossesMidnight() ? "+1" : "") + ")")).toList()));
            }
        }
    }
}
