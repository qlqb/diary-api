package com.jungwoo.project.memo.commitment.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 일회성 시간 점유(약속). one_off_commitments 테이블과 1:1 대응하는 MyBatis 엔티티.
 *
 * <p>의미는 하나다: <b>이 시간 구간은 다른 일에 쓸 수 없다.</b> 친구 약속, 병원, 면접,
 * 외출, 행사가 여기 들어온다.
 *
 * <p><b>수행 대상이 아니다.</b> ExecutionRecord를 만들지 않고 completionPercent도, DONE ·
 * PARTIAL · HOLD · REDUCE도 없다. 약속을 "완료"하는 것은 뜻이 통하지 않는다 — 약속은
 * 지키거나 안 지키는 것이지 진도가 나가는 것이 아니고, 앱이 여기서 얻어야 할 정보는
 * "그 시간에 다른 걸 넣지 마라" 하나뿐이다.
 *
 * <p><b>반복은 여기 없다.</b> 반복이면 {@code Routine}이다. 요일·유효기간·rrule 어느 것도
 * 이 클래스에 두지 않는다.
 *
 * <p>시각이 {@link LocalDateTime}이라 자정 넘김이 규칙 없이 표현된다 — 22:00~다음날 02:00은
 * 그냥 두 값이다. 루틴처럼 {@code end <= start}를 다음 날로 읽는 추론이 필요 없다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Commitment {

    private Long commitmentId;

    private Long userId;

    private String title;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    /** 어디서 만나는지. 없어도 유효하다 — 모르면 채우지 않는다. */
    private String locationText;

    /** 서버가 정한다. 클라이언트 payload로 출처를 위조할 자리를 만들지 않는다. */
    private CommitmentSourceType sourceType;

    /**
     * 낙관적 락 전용 필드. 공식 변경마다 1 증가한다.
     *
     * <p>루틴에는 없는 것을 여기에 두는 이유는 쓰기 경로가 둘이기 때문이다 — 일정 화면의
     * 직접 추가와 AI 후보 승인. 화면 하나가 아니면 "내가 보고 있던 값이 아직 그 값인가"를
     * 물어야 한다.
     */
    private Long version;

    private Boolean isDeleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
