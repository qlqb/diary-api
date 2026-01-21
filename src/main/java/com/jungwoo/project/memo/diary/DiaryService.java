package com.jungwoo.project.memo.diary;

import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.diary.domain.Diary;
import com.jungwoo.project.memo.diary.domain.DiaryRevision;
import com.jungwoo.project.memo.diary.dto.DiaryCreateRequest;
import com.jungwoo.project.memo.diary.dto.DiaryResponse;
import com.jungwoo.project.memo.diary.dto.DiaryUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class DiaryService {

    private final DiaryMapper diaryMapper;
    private final DiaryRevisionMapper diaryRevisionMapper;

    public DiaryService(DiaryMapper diaryMapper,
                        DiaryRevisionMapper diaryRevisionMapper) {
        this.diaryMapper = diaryMapper;
        this.diaryRevisionMapper = diaryRevisionMapper;
    }

    /**
     * 일기 생성
     */
    @Transactional
    public void createDiary(DiaryCreateRequest request) {
        Diary diary = new Diary();
        diary.setUserId(1L); // TODO 로그인 연동
        diary.setWrittenDate(request.getWrittenDate());
        diary.setTitle(request.getTitle());
        diary.setContent(request.getContent());
        diary.setMood(request.getMood());
        diary.setVisibility(request.getVisibility());
        diary.setWeather(request.getWeather());
        diary.setFavorite(false);
        diary.setDeleted(false);

        diaryMapper.insertDiary(diary);
    }

    /**
     * 일기 단건 조회
     */
    public DiaryResponse getDiary(Long diaryId) {
        Diary diary = diaryMapper.findById(diaryId);
        if (diary == null || diary.getDeleted()) {
            throw new NotFoundException("Diary not found. diaryId=" + diaryId);
        }
        return toResponse(diary);
    }

    /**
     * 사용자별 일기 목록 조회
     */
    public List<DiaryResponse> getDiariesByUser(Long userId) {
        List<Diary> diaries = diaryMapper.findByUser(userId);

        return diaries.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 일기 수정 (Revision 저장)
     */
    @Transactional
    public void updateDiary(Long diaryId, DiaryUpdateRequest request) {
        Diary current = diaryMapper.findById(diaryId);
        if (current == null || current.getDeleted()) {
            throw new NotFoundException("Diary not found. diaryId=" + diaryId);
        }

        // 1️⃣ 기존 상태를 revision으로 저장
        DiaryRevision revision = new DiaryRevision();
        revision.setDiaryId(current.getDiaryId());
        revision.setTitle(current.getTitle());
        revision.setContent(current.getContent());
        revision.setMood(current.getMood());

        diaryRevisionMapper.insertRevision(revision);

        // 2️⃣ diary 업데이트
        current.setTitle(request.getTitle());
        current.setContent(request.getContent());
        current.setMood(request.getMood());
        current.setVisibility(request.getVisibility());
        current.setWeather(request.getWeather());

        diaryMapper.updateDiary(current);
    }

    /**
     * 일기 삭제 (soft delete)
     */
    @Transactional
    public void deleteDiary(Long diaryId) {
        Diary diary = diaryMapper.findById(diaryId);
        if (diary == null || diary.getDeleted()) {
            throw new NotFoundException("Diary not found. diaryId=" + diaryId);
        }
        diaryMapper.softDelete(diaryId);
    }

    /**
     * Entity → Response DTO
     */
    private DiaryResponse toResponse(Diary diary) {
        return new DiaryResponse(
                diary.getDiaryId(),
                diary.getWrittenDate(),
                diary.getTitle(),
                diary.getContent(),
                diary.getMood(),
                diary.getVisibility(),
                diary.getFavorite(),
                diary.getCreatedAt(),
                diary.getUpdatedAt()
        );
    }
}
