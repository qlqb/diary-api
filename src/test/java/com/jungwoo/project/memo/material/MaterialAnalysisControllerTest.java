package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisStatus;
import com.jungwoo.project.memo.material.dto.MaterialAnalysisResponse;
import com.jungwoo.project.memo.material.dto.MaterialAnalysisStartResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 분석 시작 요청의 상태 코드만 고정한다.
 *
 * <p>새로 만들었으면 201, 검토 중이던 DRAFT를 그대로 돌려줬으면 200이다. 본문은 두 경우가
 * 같아서 상태 코드가 유일한 구분이고, 그래서 여기서 어긋나면 밖에서는 알 방법이 없다.
 */
@ExtendWith(MockitoExtension.class)
class MaterialAnalysisControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long COURSE_ID = 10L;
    private static final Long MATERIAL_ID = 100L;

    @Mock
    private MaterialAnalysisService materialAnalysisService;

    @InjectMocks
    private MaterialAnalysisController controller;

    private final UserPrincipal principal = new UserPrincipal(USER_ID, "a@example.com", "USER");

    @Test
    void analyze_returns201_whenNewDraftCreated() {
        when(materialAnalysisService.analyze(USER_ID, COURSE_ID, MATERIAL_ID))
                .thenReturn(MaterialAnalysisStartResult.created(response()));

        ResponseEntity<MaterialAnalysisResponse> result =
                controller.analyze(principal, COURSE_ID, MATERIAL_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void analyze_returns200_whenExistingDraftReused() {
        when(materialAnalysisService.analyze(USER_ID, COURSE_ID, MATERIAL_ID))
                .thenReturn(MaterialAnalysisStartResult.reused(response()));

        ResponseEntity<MaterialAnalysisResponse> result =
                controller.analyze(principal, COURSE_ID, MATERIAL_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 본문 형식은 두 경우가 같다 — 화면이 하는 일(검토 폼을 연다)이 같기 때문이다.
        assertThat(result.getBody()).isNotNull();
    }

    private MaterialAnalysisResponse response() {
        return MaterialAnalysisResponse.builder()
                .analysisId(7L)
                .courseId(COURSE_ID)
                .materialId(MATERIAL_ID)
                .status(MaterialAnalysisStatus.DRAFT)
                .build();
    }
}
