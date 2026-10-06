package com.jungwoo.project.memo.ai.draft.resolver;

import com.jungwoo.project.memo.commitment.domain.Commitment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * one_off_commitments 중 <b>원본</b> 근무(기간 안). 순수 함수.
 *
 * <p>약속에는 분류 컬럼이 없다(title/start_at/end_at/location_text/source_type뿐). §7 결정대로
 * 제목 키워드 상수로 고른다. 키워드는 여기 한 곳에만 있다.
 *
 * <p><b>파생 이동은 근무가 아니다.</b> 이 기능이 만드는 블록의 제목이 "근무 후 이동"이라
 * {@link #WORK_TITLE}에 그대로 걸린다 — 막지 않으면 이동 블록 뒤에 또 이동이 붙는다. 판단
 * 근거는 {@code derived_from_commitment_id}이고, 제목 규칙({@link #DERIVED_TITLE})은 그 컬럼이
 * 생기기 전에 만들어진 레거시 행만 걸러내는 보조 장치다. 새로 만든 파생 이동은 사용자가
 * 제목을 "퇴근길"로 바꿔도 컬럼 때문에 다시 뽑히지 않는다.
 *
 * <p>생성 출처(source_type)로는 거르지 않는다. 근무표 이미지 인식으로 등록한 진짜 근무도
 * AI_SUGGESTION_APPROVED다 — 출처와 파생 관계는 다른 사실이다.
 */
public final class UpcomingWorkShiftsResolver {

    /** "근무", "알바", "출근"이 제목에 있으면 근무로 본다. */
    public static final Pattern WORK_TITLE = Pattern.compile("근무|알바|출근");

    /**
     * 파생 컬럼이 없던 시절에 만들어진 이동·준비 블록을 제목으로 걸러낸다. 보조 장치다 —
     * 새 파생 이동의 방어선은 {@code derived_from_commitment_id}이지 이 규칙이 아니다.
     */
    public static final Pattern DERIVED_TITLE = Pattern.compile("이동|준비|휴식");

    private UpcomingWorkShiftsResolver() {
    }

    /**
     * 근무 한 건.
     *
     * <p>이동 블록의 시각은 여기서 나온다: 앞이면 {@code startAt}이 이동의 종료가 되고, 뒤면
     * {@code endAt}이 이동의 시작이 된다. 그래서 뒤쪽 이동은 {@code endAt}이 쓸 수 있는
     * 값일 때만 계산할 수 있다.
     */
    public record WorkShift(Long commitmentId, String title, LocalDateTime startAt, LocalDateTime endAt) {

        /** 종료 시각으로 뒤쪽 이동을 계산할 수 있는가. 없거나 시작보다 이르면 못 쓴다. */
        public boolean hasUsableEnd() {
            return endAt != null && endAt.isAfter(startAt);
        }
    }

    /**
     * 기간 안의 근무를 쓸 수 있는 것과 없는 것으로 나눈 결과.
     *
     * <p><b>어느 경계가 무엇을 보장하는가.</b> one_off_commitments는 {@code end_at NOT NULL}과
     * {@code chk_commitments_time CHECK (start_at < end_at)}을 걸고 있어, DB에서 온 근무는
     * incomplete가 될 수 없다. 그래서 이 구분은 DB 경로에서는 항상 비어 있고, 여기서는 그
     * 제약을 믿지 않는 방어선으로만 남는다 — 이미지 인식·다른 입력 경로가 생기거나 제약이
     * 완화될 때 잘못된 시각으로 이동을 만들지 않기 위해서다. 제약을 느슨하게 바꾸지 않는다.
     *
     * <p>둘을 합쳐 버리면 "근무가 없다"와 "근무는 있는데 종료 시각이 이상하다"가 같은 상태가
     * 되고, 사용자는 등록해 둔 근무를 앱이 못 본다고 생각하게 된다.
     *
     * @param usable     시작·종료가 모두 정상인 근무
     * @param incomplete 종료 시각이 없거나 시작보다 이른 근무. 뒤쪽 이동을 만들 수 없다
     */
    public record Result(List<WorkShift> usable, List<WorkShift> incomplete) {
        public static final Result EMPTY = new Result(List.of(), List.of());
    }

    /**
     * @param commitments 기간과 겹치는 약속 전부(CommitmentService.findOverlapping 결과)
     * @param from        첫 날(포함)
     * @param to          마지막 날(포함)
     */
    public static Result resolve(List<Commitment> commitments, LocalDate from, LocalDate to) {
        List<WorkShift> usable = new ArrayList<>();
        List<WorkShift> incomplete = new ArrayList<>();
        if (commitments == null || from == null || to == null) {
            return Result.EMPTY;
        }
        for (Commitment commitment : commitments) {
            if (!isOriginWorkShift(commitment)) {
                continue;
            }
            LocalDate day = commitment.getStartAt().toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) {
                continue;
            }
            WorkShift shift = new WorkShift(commitment.getCommitmentId(), commitment.getTitle(),
                    commitment.getStartAt(), commitment.getEndAt());
            (shift.hasUsableEnd() ? usable : incomplete).add(shift);
        }
        usable.sort(Comparator.comparing(WorkShift::startAt));
        incomplete.sort(Comparator.comparing(WorkShift::startAt));
        return new Result(usable, incomplete);
    }

    /** 제목이 근무이고, 다른 근무에서 파생된 블록이 아닌가. */
    static boolean isOriginWorkShift(Commitment commitment) {
        if (commitment == null || commitment.getStartAt() == null || commitment.getTitle() == null) {
            return false;
        }
        if (commitment.getDerivedFromCommitmentId() != null) {
            return false;
        }
        if (!WORK_TITLE.matcher(commitment.getTitle()).find()) {
            return false;
        }
        return !DERIVED_TITLE.matcher(commitment.getTitle()).find();
    }
}
