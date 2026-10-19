package com.jungwoo.project.memo.ai.draft;

import com.jungwoo.project.memo.commitment.domain.DerivedTravelRelation;

import java.time.LocalDateTime;

/**
 * 어떤 근무에 이미 붙어 있는 이동 블록. 적용된 약속일 수도, 아직 검토 중인 후보일 수도 있다.
 *
 * <p><b>존재 여부만으로 "같은 요청"이라고 판단하지 않기 위해 구간을 들고 다닌다.</b> 원본 id와
 * 방향만 보면 기존 30분 이동이 새 60분 요청을 조용히 막는다 — 사용자는 60분을 달라고 했는데
 * 앱은 "이미 있어요"라고 답하고, 30분은 그대로 남는다.
 *
 * <p>제목은 동일성 판단에 쓰지 않는다. 사용자가 "퇴근길"로 바꿔도 같은 이동이다.
 *
 * @param originCommitmentId 기준이 된 근무
 * @param relation           근무의 앞인가 뒤인가
 * @param source             이미 일정이 된 것인가, 아직 검토 중인 후보인가
 * @param referenceId        APPLIED면 commitment_id, PROPOSED면 suggestion_id
 * @param startAt            실제 저장된 이동 시작
 * @param endAt              실제 저장된 이동 종료
 */
public record ExistingTravel(
        Long originCommitmentId,
        DerivedTravelRelation relation,
        Source source,
        Long referenceId,
        LocalDateTime startAt,
        LocalDateTime endAt
) {

    /** 이미 일정인가, 아직 카드인가. 사용자에게 안내할 문구와 다음 행동이 다르다. */
    public enum Source {
        /** one_off_commitments에 저장됨. 고치려면 일정 화면에서 그 블록을 연다. */
        APPLIED,
        /** 아직 PROPOSED 후보. 검토 카드에서 고치거나 버릴 수 있다. */
        PROPOSED
    }

    public static String key(Long originCommitmentId, DerivedTravelRelation relation) {
        return originCommitmentId + ":" + relation.name();
    }

    public String key() {
        return key(originCommitmentId, relation);
    }

    /** 요청이 만들려는 구간과 실제로 같은가. 분 단위 시각 그대로 비교한다. */
    public boolean matches(LocalDateTime wantedStart, LocalDateTime wantedEnd) {
        return startAt != null && endAt != null
                && startAt.equals(wantedStart) && endAt.equals(wantedEnd);
    }

    public long minutes() {
        return startAt == null || endAt == null ? 0
                : java.time.Duration.between(startAt, endAt).toMinutes();
    }
}
