package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.learning.events.EventVocabulary.Actor;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Evidence;
import com.jungwoo.project.memo.learning.events.EventVocabulary.OriginKind;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Verb;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 자료를 과목에 연결 → RELEASED(MATERIAL) (계획 §3).
 *
 * <p>업로드·연결 시각은 교수의 공개 시각이 아니다 — payload의 uploadedAt은 연결 시각일 뿐이고, 공개 시각은 따로 알 때만 쓴다(설계 §9).
 * 상담 사진은 수업자료 공개가 아니라 제외한다. 연결을 끊거나 자료를 지워도 이벤트는 지우지 않는다(그때의 사실).
 */
@Component
@RequiredArgsConstructor
public class MaterialEventRecorder {

    private final EventSourceMapper sourceMapper;
    private final LearningEventMapper eventMapper;
    private final LearningEventWriter writer;

    @Transactional(propagation = Propagation.MANDATORY)
    public void linked(long userId, long materialId, long courseId) {
        Long linkId = sourceMapper.findMaterialLinkId(userId, materialId, courseId);
        if (linkId != null) {
            record(userId, linkId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long userId, long linkId) {
        EventSourceMapper.MaterialLinkRow link = sourceMapper.findMaterialLink(userId, linkId);
        if (link == null || "CONSULT_PHOTO".equals(link.origin())
                || eventMapper.countOwnedCourse(userId, link.courseId()) != 1) {
            return;
        }
        EventDraft released = EventDraft.of(Actor.CLASS, Verb.RELEASED, SourceRef.material(link.materialId()),
                new Payloads.Released(link.linkedAt() == null ? null : link.linkedAt().toString(), link.materialType()),
                Evidence.LOGGED);
        writer.write(new EventOrigin(userId, link.courseId(), OriginKind.MATERIAL_LINK, linkId), List.of(released));
    }
}
