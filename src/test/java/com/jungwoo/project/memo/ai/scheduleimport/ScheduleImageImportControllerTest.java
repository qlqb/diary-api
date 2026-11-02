package com.jungwoo.project.memo.ai.scheduleimport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** 크기 초과와 형식 거절은 다른 오류로 알린다 — 같은 문구면 사용자가 형식을 의심한다. */
class ScheduleImageImportControllerTest {

    private final ScheduleImageImportService service = mock(ScheduleImageImportService.class);
    private final ScheduleImageImportController controller = new ScheduleImageImportController(service);
    private final UserPrincipal principal = new UserPrincipal(1L, "a@b.c", "USER");

    @Test
    void 상한을_넘으면_크기_초과로_알린다() {
        MockMultipartFile big = new MockMultipartFile("image", "s.png", "image/png", new byte[5 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> controller.extract(principal, 1L, big))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
        verifyNoInteractions(service);
    }

    @Test
    void 형식이_다르면_형식_오류로_알린다() {
        MockMultipartFile gif = new MockMultipartFile("image", "s.gif", "image/gif", new byte[10]);

        assertThatThrownBy(() -> controller.extract(principal, 1L, gif))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_IMAGE);
    }
}
