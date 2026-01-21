package com.jungwoo.project.memo.diary;

import com.jungwoo.project.memo.diary.domain.Diary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DiaryMapper {

    /**
     * 일기 단건 조회 (삭제 제외는 서비스에서 판단)
     */
    Diary findById(@Param("diaryId") Long diaryId);

    /**
     * 사용자별 일기 목록 조회
     */
    List<Diary> findByUser(@Param("userId") Long userId);

    /**
     * 일기 생성
     */
    int insertDiary(Diary diary);

    /**
     * 일기 수정
     */
    int updateDiary(Diary diary);

    /**
     * 소프트 삭제
     */
    int softDelete(@Param("diaryId") Long diaryId);
}
