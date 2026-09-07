package com.jungwoo.project.memo.ai.draft.resolver;

import com.jungwoo.project.memo.commitment.domain.Commitment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * one_off_commitments 중 근무 성격의 항목(기간 안). 순수 함수.
 *
 * <p>약속에는 분류 컬럼이 없다(title/start_at/end_at/location_text/source_type뿐). §7 결정대로
 * 제목 키워드 상수로 고른다. 키워드는 여기 한 곳에만 있다.
 */
public final class UpcomingWorkShiftsResolver {

    /** "근무", "알바", "출근"이 제목에 있으면 근무로 본다. */
    public static final Pattern WORK_TITLE = Pattern.compile("근무|알바|출근");

    private UpcomingWorkShiftsResolver() {
    }

    /** 근무 한 건. 시작 시각이 이동 블록의 종료 시각이 된다. */
    public record WorkShift(Long commitmentId, String title, LocalDateTime startAt, LocalDateTime endAt) {
    }

    /**
     * @param commitments 기간과 겹치는 약속 전부(CommitmentService.findOverlapping 결과)
     * @param from        첫 날(포함)
     * @param to          마지막 날(포함)
     */
    public static List<WorkShift> resolve(List<Commitment> commitments, LocalDate from, LocalDate to) {
        List<WorkShift> shifts = new ArrayList<>();
        if (commitments == null || from == null || to == null) {
            return shifts;
        }
        for (Commitment commitment : commitments) {
            if (commitment == null || commitment.getStartAt() == null || commitment.getTitle() == null) {
                continue;
            }
            if (!WORK_TITLE.matcher(commitment.getTitle()).find()) {
                continue;
            }
            LocalDate day = commitment.getStartAt().toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) {
                continue;
            }
            shifts.add(new WorkShift(commitment.getCommitmentId(), commitment.getTitle(),
                    commitment.getStartAt(), commitment.getEndAt()));
        }
        shifts.sort(Comparator.comparing(WorkShift::startAt));
        return shifts;
    }
}
