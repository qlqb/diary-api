package com.jungwoo.project.memo.ai.photo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** consult_photo_uploads — 사진 한 장 업로드의 선점·결과. 같은 업로드 키의 재전송은 이 행으로 결과를 돌려준다. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsultPhotoUpload {

    public static final String PROCESSING = "PROCESSING";
    public static final String READ = "READ";
    public static final String UNREADABLE = "UNREADABLE";
    public static final String FAILED = "FAILED";

    private Long uploadId;
    private Long userId;
    private Long conversationId;
    private String uploadKey;
    private String status;
    private String storagePath;
    private Long materialId;
    private String resultJson;
    private LocalDateTime fileRemovedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
