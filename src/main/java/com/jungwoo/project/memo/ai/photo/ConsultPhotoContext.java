package com.jungwoo.project.memo.ai.photo;

import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicLinkOrigin;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 상담 턴의 사진 문맥. 활성 사진 = 이 대화에서 가장 최근에 사진을 붙인 사용자 메시지의 사진(새 사진을 붙이기 전까지 유지).
 *
 * <p>두 곳에 쓴다: 근거 고정(사진 본문을 검색어와 무관하게 싣는다), "이 문제 모르겠어"처럼 사진을 가리키는 기억의 단원.
 * 실패해도 상담을 막지 않는다(빈 문맥).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsultPhotoContext {

    /** 사진을 가리키는 말. 이 말이 있어야 사진의 단원을 기억에 채운다. */
    static final Pattern DEICTIC = Pattern.compile(
            "이\\s*(?:문제|거|것|부분|문장|사진|단원|페이지|쪽|빈칸|번)|이거|이게|여기|요거|저\\s*(?:문제|부분|거)|\\d{1,2}\\s*번");

    private final AiMessagePhotoMapper messagePhotoMapper;
    private final TopicMaterialLinkMapper topicLinkMapper;
    private final CourseTopicMapper courseTopicMapper;

    /**
     * @param guessed 단원 연결이 서버 추정(PHOTO_GUESS)이다
     */
    public record ActivePhoto(Long materialId, Long topicId, String topicTitle, boolean guessed) {
    }

    /** 사진에서 유도한 기억의 단원(사진 하나 또는 같은 단원을 가리키는 여러 장). */
    public record Reference(Long topicId, Long photoId, List<Long> allPhotoIds) {
    }

    public List<ActivePhoto> active(Long conversationId, Long courseId, Long userId, Long uptoMessageId) {
        if (conversationId == null || uptoMessageId == null) {
            return List.of();
        }
        try {
            List<Long> ids = messagePhotoMapper.findActivePhotoIds(conversationId, userId, uptoMessageId);
            if (ids.isEmpty()) {
                return List.of();
            }
            Map<Long, CourseTopic> topics = courseId == null ? Map.of()
                    : courseTopicMapper.findActiveByCourseIdAndUserId(courseId, userId).stream()
                    .collect(Collectors.toMap(CourseTopic::getTopicId, Function.identity(), (a, b) -> a));
            List<ActivePhoto> out = new ArrayList<>();
            for (Long id : ids) {
                TopicMaterialLink link = topicLinkMapper.findActiveByMaterialId(id, userId).stream()
                        .filter(l -> topics.containsKey(l.getTopicId())).findFirst().orElse(null);
                out.add(link == null ? new ActivePhoto(id, null, null, false)
                        : new ActivePhoto(id, link.getTopicId(), topics.get(link.getTopicId()).getTitle(),
                        link.getOrigin() == TopicLinkOrigin.PHOTO_GUESS));
            }
            return out;
        } catch (Exception e) {
            log.warn("상담 사진 문맥을 읽지 못함(사진 없이 진행): conversationId={}", conversationId, e);
            return List.of();
        }
    }

    /** 근거 고정용: 자료 id → 단원 표시. */
    public static Map<Long, String> pinned(List<ActivePhoto> photos) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (ActivePhoto p : photos) {
            out.put(p.materialId(), p.topicTitle() == null ? "" : p.topicTitle() + (p.guessed() ? "(사진 단원 추정)" : ""));
        }
        return out;
    }

    /**
     * 사진을 가리키는 발화면 그 사진의 단원. 활성 사진이 모두 같은 단원 하나에 연결돼 있을 때만(여럿이면 어느 것인지 모른다).
     */
    public static Reference reference(List<ActivePhoto> photos, String userMessage) {
        if (photos.isEmpty() || userMessage == null || !DEICTIC.matcher(userMessage).find()) {
            return null;
        }
        List<Long> topics = photos.stream().map(ActivePhoto::topicId).distinct().toList();
        if (topics.size() != 1 || topics.get(0) == null) {
            return null;
        }
        Long photoId = photos.stream().filter(p -> Objects.equals(p.topicId(), topics.get(0)))
                .map(ActivePhoto::materialId).findFirst().orElse(null);
        return new Reference(topics.get(0), photoId, photos.stream().map(ActivePhoto::materialId).toList());
    }
}
