package com.jungwoo.project.memo.course.textbook.web;

import com.jungwoo.project.memo.course.textbook.TextbookChangedEvent;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.analysis.MaterialContentAnalyzedEvent;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 교재 근거가 바뀌면(교재 칸·목차 연결·검색 켜고 끄기, 자료 분석 완료) 커밋 뒤에 조회를 새 근거로 맞춘다.
 * 화면을 열지 않아도 강의계획서를 올리면 서버가 교재를 찾기 시작한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextbookLookupEvents {

    private final TextbookLookupService lookupService;
    private final MaterialLinkMapper linkMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTextbookChanged(TextbookChangedEvent event) {
        ensure(event.userId(), event.courseId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onContentAnalyzed(MaterialContentAnalyzedEvent event) {
        for (MaterialLink link : linkMapper.findByMaterialIdAndUserId(event.materialId(), event.userId())) {
            ensure(event.userId(), link.getCourseId());
        }
    }

    private void ensure(Long userId, Long courseId) {
        try {
            lookupService.ensureAfterCommit(userId, courseId);
        } catch (Exception e) {
            // 조회 등록이 실패해도 원래 일(교재 저장·분석)은 이미 끝났다. 다음 화면 조회가 다시 맞춘다.
            log.warn("교재 조회 등록 실패: courseId={}, {}", courseId, e.getClass().getSimpleName(), e);
        }
    }
}
