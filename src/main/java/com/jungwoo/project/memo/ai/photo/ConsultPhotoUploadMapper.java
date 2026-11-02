package com.jungwoo.project.memo.ai.photo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ConsultPhotoUploadMapper {

    /** 선점. 같은 (user_id, upload_key)가 이미 있으면 0. */
    int insertIgnore(ConsultPhotoUpload upload);

    /** 저장 트랜잭션에서 업로드 행을 잠근다(상태를 확인한 뒤 바뀌지 않게). */
    ConsultPhotoUpload lockById(@Param("uploadId") Long uploadId);

    ConsultPhotoUpload findByKey(@Param("userId") Long userId, @Param("uploadKey") String uploadKey);

    int recordStoragePath(@Param("uploadId") Long uploadId, @Param("storagePath") String storagePath);

    /** 처리 중인 행만 끝낸다(늦게 끝난 처리가 이미 정리된 행을 되살리지 못하게). */
    int finish(@Param("uploadId") Long uploadId, @Param("status") String status, @Param("materialId") Long materialId,
               @Param("resultJson") String resultJson);

    /** 오래 처리 중인 행을 FAILED로 내린다(서버가 처리 도중 멈춘 경우). */
    int failStale(@Param("uploadId") Long uploadId, @Param("before") LocalDateTime before);

    int failAllStale(@Param("before") LocalDateTime before);

    /** 자료가 되지 못한 업로드 중 파일을 아직 못 지운 것. */
    List<ConsultPhotoUpload> findFilesToRemove(@Param("limit") int limit);

    int markFileRemoved(@Param("uploadId") Long uploadId, @Param("now") LocalDateTime now);
}
