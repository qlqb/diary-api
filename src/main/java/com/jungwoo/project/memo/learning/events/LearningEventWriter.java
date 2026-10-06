package com.jungwoo.project.memo.learning.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.learning.events.EventOutputs.Output;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 학습 이벤트를 쓰는 유일한 입구(설계 20번 §6.3, 계획 §2.1).
 *
 * <p>원본(실행 기록·기억 행 …)을 쓴 <b>같은 트랜잭션</b>에서 부른다 — 이 기록이 실패하면 원본 쓰기도 롤백된다(이중 기록 누락 0).
 *
 * <ul>
 *   <li>origin마다 판이 있다. 새로 계산한 출력이 지금 판과 같은 뜻이면 아무것도 하지 않는다(재시도·같은 값 저장이 판을 올리지 않는다).
 *       다르면 판을 1 올려 출력 전체를 새로 쓴다. 출력 0개도 판이다(지움·해제 — 이전 판이 내려간다).</li>
 *   <li>잠금: 호출자가 이미 잡은 잠금(과목 행·원본 행) 다음에 origin 행을 잡는다. 이전 판도 잠금 읽기로 읽는다 — 격리 수준과 무관하게
 *       최신 커밋 값을 본다.</li>
 *   <li>origin 행은 잠금 자리로 먼저 만든다(판 0). 처음 쓰는 출력이 0개면 판을 올리지 않는다 — 판 0인 origin은 "쓴 적 없음"이라
 *       나중 백필을 막지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningEventWriter {

    private final LearningEventMapper mapper;
    private final LearningEventValidator validator;
    private final ObjectMapper objectMapper;

    public enum Result { UNCHANGED, WRITTEN, SKIPPED_EMPTY }

    /**
     * 이 origin에 이미 쓴 판이 있나(백필이 "실시간이 먼저 썼나"를 볼 때). origin 행을 잠근 채로 돌려준다 — 같은 트랜잭션에서 이어 쓰는
     * 동안 실시간 경로가 끼지 않는다. 과목이 다르게 기록된 origin이면 이미 있는 것으로 본다(건드리지 않는다).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean written(EventOrigin origin) {
        String kind = origin.kind().name();
        mapper.upsertOrigin(origin.userId(), kind, origin.id(), origin.courseId());
        LearningEventMapper.OriginRow row = mapper.lockOrigin(origin.userId(), kind, origin.id());
        return row.courseId() != origin.courseId() || row.currentRevision() > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result write(EventOrigin origin, List<EventDraft> drafts) {
        List<Output> outputs = EventOutputs.normalize(drafts, objectMapper);
        String kind = origin.kind().name();

        // 행을 먼저 만들고(없으면 판 0) 잠근다. 없는 행을 잠금 읽기하면 간격 잠금이 남아 다른 origin의 삽입과 교착할 수 있다.
        mapper.upsertOrigin(origin.userId(), kind, origin.id(), origin.courseId());
        LearningEventMapper.OriginRow row = mapper.lockOrigin(origin.userId(), kind, origin.id());
        if (row.courseId() != origin.courseId()) {
            throw new IllegalStateException("이벤트 원본의 과목은 바꿀 수 없다: " + kind);
        }

        int current = row.currentRevision();
        if (current == 0 && outputs.isEmpty()) {
            return Result.SKIPPED_EMPTY; // 판 0 = 쓴 적 없음. 백필은 판 0인 origin을 "없음"으로 본다
        }
        List<Output> existing = current == 0 ? List.of()
                : EventOutputs.fromRows(mapper.lockOutputs(origin.userId(), kind, origin.id(), current), objectMapper);
        if (current > 0 && EventOutputs.sameOutputs(existing, outputs)) {
            return Result.UNCHANGED;
        }
        validator.validate(origin, outputs, existing);

        int next = current + 1;
        if (!outputs.isEmpty()) {
            List<LearningEvent> rows = new ArrayList<>();
            for (int i = 0; i < outputs.size(); i++) {
                Output o = outputs.get(i);
                rows.add(LearningEvent.builder()
                        .userId(origin.userId()).courseId(origin.courseId())
                        .originKind(kind).originId(origin.id()).originRevision(next).outputNo(i + 1)
                        .actor(o.actor()).verb(o.verb()).objectKind(o.objectKind()).objectRef(o.objectRef())
                        .objectFrom(o.from()).objectTo(o.to()).topicId(o.topicId()).payload(o.payloadJson())
                        .evidence(o.evidence()).confidence(o.confidence()).claimAt(o.claimAt()).claimSeq(o.claimSeq())
                        .occurredAt(o.occurredAt())
                        .build());
            }
            mapper.insertEvents(rows);
        }
        mapper.updateOriginRevision(origin.userId(), kind, origin.id(), next);
        return Result.WRITTEN;
    }
}
