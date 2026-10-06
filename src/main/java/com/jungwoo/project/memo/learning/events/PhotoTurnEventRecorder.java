package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.learning.events.EventVocabulary.Actor;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Evidence;
import com.jungwoo.project.memo.learning.events.EventVocabulary.OriginKind;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Verb;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 사진을 붙여 같이 공부한 상담 턴 → 사진 구간마다 STUDIED{PHOTO_TURN}(계획 §3).
 *
 * <p>턴이 <b>성공으로 끝난</b> 트랜잭션에서만 부른다 — 실패한 턴, 업로드만 한 사진은 학습 활동이 아니다(19번: 업로드 ≠ 학습).
 * 과목 상담이 아니면 대상 밖. 사진 자료를 과목에서 떼어 냈으면 그 사진은 뺀다.
 */
@Component
@RequiredArgsConstructor
public class PhotoTurnEventRecorder {

    private final EventSourceMapper sourceMapper;
    private final LearningEventMapper eventMapper;
    private final EventSourceResolver resolver;
    private final LearningEventWriter writer;

    @Transactional(propagation = Propagation.MANDATORY)
    public void turnCompleted(long userId, long requestMessageId) {
        Long courseId = sourceMapper.findCourseOfMessage(userId, requestMessageId);
        if (courseId == null || eventMapper.countOwnedCourse(userId, courseId) != 1) {
            return;
        }
        List<Long> sections = new ArrayList<>();
        for (Long photo : sourceMapper.findPhotosOfMessage(userId, requestMessageId)) {
            Long section = sourceMapper.findPhotoSection(userId, photo);
            if (section != null) {
                sections.add(section);
            }
        }
        List<EventDraft> drafts = new ArrayList<>();
        for (Long section : resolver.usableSections(userId, courseId, sections, Set.of())) {
            drafts.add(EventDraft.of(Actor.ME, Verb.STUDIED, SourceRef.section(section),
                    new Payloads.Studied("PHOTO_TURN"), Evidence.LOGGED));
        }
        writer.write(new EventOrigin(userId, courseId, OriginKind.MESSAGE_PHOTO, requestMessageId), drafts);
    }
}
