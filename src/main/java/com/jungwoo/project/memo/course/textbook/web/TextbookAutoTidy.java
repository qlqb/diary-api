package com.jungwoo.project.memo.course.textbook.web;

import com.jungwoo.project.memo.learning.tidy.ProjectTidyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 목차를 확보한 조회(auto_tidy_state=PENDING·WAITING_OPEN_PROPOSAL)마다 정리안 자동 생성을 다시 본다. 폴러 틱마다 돈다 —
 * 결과 저장 직후 서버가 멈췄거나, 그때 다른 정리가 돌고 있었어도 다음 틱에 이어진다.
 *
 * <ul>
 *   <li>열린 정리 작업이 있으면 기다린다(PENDING 유지).</li>
 *   <li>사용자가 검토 중인 정리안이 있으면 건드리지 않는다 — WAITING_OPEN_PROPOSAL(화면: 새 목차 반영 가능). 그 안이 같은 목차
 *       근거로 만든 것이면 DONE.</li>
 *   <li>같은 목차 근거로 이미 만든 정리안이 있으면(적용·폐기 포함) 다시 만들지 않는다 — DONE.</li>
 *   <li>아니면 정리 작업을 하나 등록한다 — ENQUEUED.</li>
 * </ul>
 * 지난 조회(새 근거로 바뀐 것)는 DONE으로 닫는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextbookAutoTidy {

    private final TextbookLookupMapper lookupMapper;
    private final ProjectTidyService tidyService;

    public void evaluate() {
        LocalDateTime now = LocalDateTime.now();
        for (TextbookLookup lookup : lookupMapper.findAutoTidyCandidates(now, 20)) {
            String from = lookup.getAutoTidyState();
            try {
                TextbookLookup latest = lookupMapper.findLatestByCourse(lookup.getCourseId(), lookup.getUserId());
                if (latest == null || !latest.getLookupId().equals(lookup.getLookupId())
                        || !TextbookLookupService.isLive(lookup)) {
                    lookupMapper.updateAutoTidy(lookup.getLookupId(), from, "DONE", null, null);
                    continue;
                }
                ProjectTidyService.AutoResult result = tidyService.requestForTextbookToc(lookup.getUserId(),
                        lookup.getCourseId());
                String to = switch (result.outcome()) {
                    case ENQUEUED -> "ENQUEUED";
                    case JOB_RUNNING -> from;
                    case OPEN_PROPOSAL -> "WAITING_OPEN_PROPOSAL";
                    case OPEN_PROPOSAL_SAME_TOC, ALREADY_DONE -> "DONE";
                    case NO_TOC -> "DONE";
                };
                // 기다리는 상태는 다음 평가를 미룬다(같은 행이 매 틱 앞을 막지 않게). 정리 작업이 도는 중이면 짧게, 사용자가
                // 검토 중인 안이 있으면 길게.
                LocalDateTime next = switch (to) {
                    case "PENDING" -> now.plusSeconds(15);
                    case "WAITING_OPEN_PROPOSAL" -> now.plusSeconds(60);
                    default -> null;
                };
                lookupMapper.updateAutoTidy(lookup.getLookupId(), from, to, result.jobId(), next);
            } catch (Exception e) {
                log.warn("교재 목차 자동 정리 평가 실패: lookupId={}, {}", lookup.getLookupId(), e.getClass().getSimpleName());
                lookupMapper.updateAutoTidy(lookup.getLookupId(), from, from, null, now.plusMinutes(5));
            }
        }
    }
}
