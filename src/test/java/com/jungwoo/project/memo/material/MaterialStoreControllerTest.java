package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.material.linkproposal.MaterialLinkProposalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 원본 파일 응답의 헤더만 고정한다.
 *
 * <p>이 앱의 자료는 파일명이 거의 한글이다. Content-Disposition에 그대로 넣으면 헤더가
 * latin-1이라 깨지므로 RFC 5987 인코딩이 유일하게 맞는 형태이고, 여기서 어긋나면 화면에는
 * 파일이 열리기는 해서 한참 뒤에야 드러난다.
 */
@ExtendWith(MockitoExtension.class)
class MaterialStoreControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long MATERIAL_ID = 100L;

    @Mock private MaterialService materialService;
    @Mock private MaterialAnalysisService materialAnalysisService;
    @Mock private MaterialLinkProposalService materialLinkProposalService;

    @InjectMocks
    private MaterialStoreController controller;

    private final UserPrincipal principal = new UserPrincipal(USER_ID, "a@example.com", "USER");

    @Test
    void file_sendsKoreanFilenameEncodedAndInline() {
        when(materialService.openFile(USER_ID, MATERIAL_ID)).thenReturn(new MaterialService.MaterialFile(
                new ByteArrayResource("%PDF-1.4".getBytes()),
                "빅데이터분석 강의계획서.pdf",
                "application/pdf",
                8L));

        ResponseEntity<Resource> result = controller.file(principal, MATERIAL_ID);

        String disposition = result.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).startsWith("inline; filename*=UTF-8''");
        // 공백은 '+'가 아니라 %20이어야 한다 — 헤더는 폼 인코딩이 아니다.
        assertThat(disposition).doesNotContain("+");
        assertThat(disposition).contains("%20");
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(result.getHeaders().getContentLength()).isEqualTo(8L);
    }

    /** 오래된 행은 content_type이 비어 있을 수 있다. 그때도 500이 아니라 파일이 나가야 한다. */
    @Test
    void file_fallsBackToOctetStreamWhenContentTypeUnknown() {
        when(materialService.openFile(USER_ID, MATERIAL_ID)).thenReturn(new MaterialService.MaterialFile(
                new ByteArrayResource("x".getBytes()), "자료.pdf", null, null));

        ResponseEntity<Resource> result = controller.file(principal, MATERIAL_ID);

        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(result.getHeaders().getContentLength()).isEqualTo(-1);
    }
}
