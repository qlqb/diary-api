package com.jungwoo.project.memo.ai.scheduleimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.scheduleimport.dto.RawScheduleTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 실제 모델에 근무표 이미지를 보내 <b>받아쓰기가 받아쓰기로 남는지</b>를 눈으로 확인하는
 * 수동 프로브. 단정문이 없다 — 확인할 것은 "값이 맞는가"가 아니라 "모델이 해석을 시작하지
 * 않는가"이고, 그건 원문을 사람이 읽어야 안다.
 *
 * <p>이미지가 없으면 통째로 건너뛴다. 이미지는 저장소에 넣지 않는다(동료들의 실명과 근무
 * 시간이 들어 있다). 확인하려면 아래 경로에 직접 놓고 돌린다.
 *
 * <p>3회 돌리는 이유는 1회로는 흔들림을 볼 수 없기 때문이다. 같은 이미지에서 같은 셀이
 * 매번 다르게 나오면 그건 프롬프트가 아직 덜 조여진 것이다.
 */
@SpringBootTest
@EnabledIf("imageExists")
class ScheduleImageVisionProbe {

    private static final Path IMAGE = Path.of("src/test/resources/schedule-import/weekly-work-schedule.png");
    private static final int RUNS = 3;

    static boolean imageExists() {
        return Files.exists(IMAGE);
    }

    @Autowired
    private ScheduleImageVisionClient visionClient;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("같은 이미지를 3회 받아쓰게 하고 원문을 그대로 남긴다")
    void transcribesTheSameImageThreeTimes() throws Exception {
        byte[] image = Files.readAllBytes(IMAGE);
        Path out = Path.of("build/schedule-import-probe");
        Files.createDirectories(out);

        for (int run = 1; run <= RUNS; run++) {
            long startedAt = System.currentTimeMillis();
            RawScheduleTable raw = visionClient.read(image, "image/png");
            long elapsed = System.currentTimeMillis() - startedAt;

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw);
            Files.writeString(out.resolve("run-" + run + ".json"), json);
            System.out.println("===== RUN " + run + " (" + elapsed + "ms) =====");
            System.out.println(json);
        }
    }
}
