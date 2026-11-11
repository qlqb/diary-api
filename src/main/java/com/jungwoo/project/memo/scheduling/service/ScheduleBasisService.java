package com.jungwoo.project.memo.scheduling.service;

import com.jungwoo.project.memo.commitment.CommitmentMapper;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 어느 기간의 실제 일정(약속·시각 고정 실행·반복 회차) 지문. 검토(미리보기)와 적용 사이에 새 일정이 생기거나 바뀌었는지 본다 — 대상
 * 행의 version만으로는 "범위 안에 새 약속이 생김"을 알 수 없다.
 *
 * <p>잠금 조회로 만들면 같은 행·범위를 잠근다(계획 확정의 PlanConfirmScheduleGuard와 같은 세 원천·같은 순서: 약속 → 실행 → 반복).
 * 검토 때는 일반 조회로, 적용 때는 잠금 조회로 같은 모양을 만들어 비교한다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleBasisService {

    private final CommitmentService commitmentService;
    private final CommitmentMapper commitmentMapper;
    private final ExecutionItemMapper executionItemMapper;
    private final RoutineOccurrenceService routineOccurrenceService;

    @Transactional(readOnly = true)
    public String fingerprint(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return null;
        }
        return digest(commitmentService.findOverlapping(userId, from, to),
                executionItemMapper.findTimeFixedByUserIdAndDateRange(userId, from, to),
                routineOccurrenceService.expand(userId, from, to));
    }

    /** 적용 트랜잭션 안에서 부른다 — 범위의 약속·실행·반복 일정을 잠근 채 지문을 만든다. */
    @Transactional
    public String lockedFingerprint(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return null;
        }
        return digest(commitmentMapper.findOverlappingForUpdate(userId, from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                executionItemMapper.findTimeFixedByUserIdAndDateRangeForUpdate(userId, from, to),
                routineOccurrenceService.expandForUpdate(userId, from, to));
    }

    static String digest(List<Commitment> commitments, List<ExecutionItem> fixed, List<RoutineOccurrence> occurrences) {
        List<String> lines = new ArrayList<>();
        commitments.forEach(c -> lines.add("C" + c.getCommitmentId() + ":" + c.getVersion() + ":" + c.getStartAt() + "~" + c.getEndAt()));
        fixed.forEach(i -> lines.add("E" + i.getExecutionItemId() + ":" + i.getVersion() + ":" + i.getScheduledStartAt()
                + "~" + i.getScheduledEndAt()));
        occurrences.forEach(o -> lines.add("R" + o.routineId() + "@" + o.sourceDate() + ":" + o.startAt() + "~" + o.endAt()));
        lines.sort(String::compareTo);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
