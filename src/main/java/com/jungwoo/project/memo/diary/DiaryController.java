package com.jungwoo.project.memo.diary;

import com.jungwoo.project.memo.diary.dto.DiaryCreateRequest;
import com.jungwoo.project.memo.diary.dto.DiaryResponse;
import com.jungwoo.project.memo.diary.dto.DiaryUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/diaries")
public class DiaryController {

    private final DiaryService diaryService;

    public DiaryController(DiaryService diaryService) {
        this.diaryService = diaryService;
    }

    /**
     * 일기 생성
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void createDiary(@RequestBody DiaryCreateRequest request) {
        diaryService.createDiary(request);
    }

    /**
     * 일기 단건 조회
     */
    @GetMapping("/{diaryId}")
    public DiaryResponse getDiary(@PathVariable Long diaryId) {
        return diaryService.getDiary(diaryId);
    }

    /**
     * 사용자별 일기 목록 조회
     */
    @GetMapping
    public List<DiaryResponse> getDiaries(
            @RequestParam Long userId
    ) {
        return diaryService.getDiariesByUser(userId);
    }

    /**
     * 일기 수정
     */
    @PutMapping("/{diaryId}")
    public void updateDiary(
            @PathVariable Long diaryId,
            @RequestBody DiaryUpdateRequest request
    ) {
        diaryService.updateDiary(diaryId, request);
    }

    /**
     * 일기 삭제 (soft delete)
     */
    @DeleteMapping("/{diaryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDiary(@PathVariable Long diaryId) {
        diaryService.deleteDiary(diaryId);
    }
}
