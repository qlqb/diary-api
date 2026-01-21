package com.jungwoo.project.memo.diary;

import com.jungwoo.project.memo.diary.domain.DiaryRevision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DiaryRevisionMapper {

    /**
     * 특정 일기의 수정 이력 조회
     */
    List<DiaryRevision> findByDiaryId(@Param("diaryId") Long diaryId);

    /**
     * 수정 이력 저장
     */
    int insertRevision(DiaryRevision diaryRevision);
}
