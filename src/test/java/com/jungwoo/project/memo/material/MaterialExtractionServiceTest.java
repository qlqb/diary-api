package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전체 텍스트 사본의 저장 한도. 자료 행은 한 쿼리로 들어가서 DB의 max_allowed_packet(1MiB)을 넘으면
 * 자료 자체가 만들어지지 않았다 — 사용자에게는 "TransientDataAccessResourceException"만 보였다.
 */
class MaterialExtractionServiceTest {

    private static MaterialExtractionService.Outcome outcome(String text, String warning) {
        return new MaterialExtractionService.Outcome(ExtractionStatus.SUCCESS, text, null, warning, List.of(), null);
    }

    @Test
    void 한도_안이면_그대로_둔다() {
        MaterialExtractionService.Outcome original = outcome("가".repeat(1000), null);

        assertThat(MaterialExtractionService.fitForStorage(original)).isSameAs(original);
    }

    @Test
    void 한도를_넘는_한글_본문은_바이트_기준으로_잘리고_경고가_남는다() {
        // 한글 1자는 UTF-8 3바이트다. 글자 수로는 한도 안처럼 보여도 바이트로는 넘는다.
        String text = "가".repeat(MaterialExtractionService.MAX_STORED_TEXT_BYTES / 2);

        MaterialExtractionService.Outcome fitted =
                MaterialExtractionService.fitForStorage(outcome(text, "실행 결과가 길어 일부만 읽었어요"));

        assertThat(fitted.text().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(MaterialExtractionService.MAX_STORED_TEXT_BYTES);
        assertThat(fitted.text()).isNotEmpty();
        assertThat(fitted.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(fitted.warning()).contains("실행 결과가 길어").contains("앞부분만 보관");
    }

    @Test
    void 이모지_같은_4바이트_문자는_반으로_가르지_않는다() {
        String text = "😀".repeat(MaterialExtractionService.MAX_STORED_TEXT_BYTES / 4 + 10);

        MaterialExtractionService.Outcome fitted = MaterialExtractionService.fitForStorage(outcome(text, null));

        assertThat(fitted.text().length() % 2).isZero();
        assertThat(Character.isHighSurrogate(fitted.text().charAt(fitted.text().length() - 1))).isFalse();
    }
}
