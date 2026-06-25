package com.jungwoo.project.memo.diary.dto;

import com.jungwoo.project.memo.diary.domain.Diary;
import com.jungwoo.project.memo.diary.domain.DiaryRevision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 수정 이력 목록 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiaryRevisionsResponse {

    /** 현재 일기 정보 */
    private DiaryResponse current;

    /** 수정 이력 목록 (최신순) */
    private List<DiaryRevisionResponse> revisions;

    /** 수정 이력 개수 */
    private int totalRevisions;

    public static DiaryRevisionsResponse of(Diary diary, List<DiaryRevision> revisions) {
        List<DiaryRevisionResponse> revisionResponses = revisions.stream()
                .map(DiaryRevisionResponse::from)
                .toList();

        return DiaryRevisionsResponse.builder()
                .current(DiaryResponse.from(diary))
                .revisions(revisionResponses)
                .totalRevisions(revisionResponses.size())
                .build();
    }
}