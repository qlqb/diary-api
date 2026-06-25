package com.jungwoo.project.memo.diary;

import com.jungwoo.project.memo.diary.domain.DiaryRevision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DiaryRevisionMapper {

    /**
     * 수정 이력 저장
     *
     * DiaryRevisionMapper.xml의 <insert id="insert">와 매핑됨
     */
    int insert(DiaryRevision revision);

    /**
     * 특정 일기의 수정 이력 목록 조회
     *
     * DiaryRevisionMapper.xml의 <select id="findByDiaryId">와 매핑됨
     */
    List<DiaryRevision> findByDiaryId(@Param("diaryId") Long diaryId);

    /**
     * 수정 이력 ID로 조회
     *
     * DiaryRevisionMapper.xml의 <select id="findById">와 매핑됨
     */
    DiaryRevision findById(@Param("revisionId") Long revisionId);
}