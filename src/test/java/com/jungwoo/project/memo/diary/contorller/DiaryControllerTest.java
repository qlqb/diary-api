package com.jungwoo.project.memo.diary.contorller;

import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.diary.DiaryController;
import com.jungwoo.project.memo.diary.DiaryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest(DiaryController.class)
@AutoConfigureMockMvc(addFilters = false)
class DiaryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiaryService diaryService;

    @Test
    void diary_not_found_returns_404() throws Exception {
        // given
        given(diaryService.getDiary(999999L))
                .willThrow(new NotFoundException("Diary not found"));

        // when & then
        mockMvc.perform(get("/api/diaries/999999"))
                .andExpect(status().isNotFound());
    }
}
