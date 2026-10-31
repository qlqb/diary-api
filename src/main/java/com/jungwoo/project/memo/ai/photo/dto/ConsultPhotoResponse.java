package com.jungwoo.project.memo.ai.photo.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 상담 사진 한 장의 상태. 올리기·재전송 복구·단원 확인/변경·원본 삭제가 모두 이 모양을 돌려준다.
 *
 * @param photoId           자료 id(READ일 때만)
 * @param status            READ / UNREADABLE(글자 없음·교재 아님) / FAILED(읽기 실패)
 * @param title             표시 이름("교재 사진 p.24 · Unit 3 …")
 * @param text              읽은 글(손글씨 표지 포함). 화면의 "읽은 글 보기"
 * @param topic             연결된 단원(없으면 null)
 * @param link              GUESSED(서버 추정) / CONFIRMED(사용자 확인) / NONE
 * @param topics            바꾸기 선택지(이 과목의 현재 교재 목차 단원, 목차가 없으면 살아 있는 단원)
 * @param originalAvailable 지금 원본 사진을 볼 수 있나
 * @param originalExpiresAt 원본 자동 삭제 시각
 */
public record ConsultPhotoResponse(Long photoId, String uploadKey, String status, String title, Integer printedPage,
                                   List<String> headings, String text, TopicRef topic, String link, List<TopicRef> topics,
                                   boolean originalAvailable, LocalDateTime originalExpiresAt, LocalDateTime createdAt) {

    public static final String READ = "READ";
    public static final String UNREADABLE = "UNREADABLE";
    public static final String FAILED = "FAILED";

    public static final String LINK_GUESSED = "GUESSED";
    public static final String LINK_CONFIRMED = "CONFIRMED";
    public static final String LINK_NONE = "NONE";

    public record TopicRef(Long topicId, String title, Integer sourceTocSeq) {
    }

    public static ConsultPhotoResponse notSaved(String uploadKey, String status) {
        return new ConsultPhotoResponse(null, uploadKey, status, null, null, List.of(), null, null, LINK_NONE, List.of(),
                false, null, null);
    }
}
