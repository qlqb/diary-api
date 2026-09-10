package com.jungwoo.project.memo.commitment;

import com.jungwoo.project.memo.commitment.domain.Commitment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommitmentMapper {

    void insert(Commitment commitment);

    /** 소프트 삭제된 것은 돌려주지 않는다. 소유권 확인과 조회가 같은 조건을 공유한다. */
    Commitment findByIdAndUserId(@Param("commitmentId") Long commitmentId,
                                 @Param("userId") Long userId);

    /**
     * 기간 조회. 조건은 "시작 시각이 범위 안"이 아니라 <b>"구간이 범위와 겹침"</b>이다.
     *
     * <p>전날 22시에 시작해 오늘 02시에 끝나는 약속은 시작 시각이 어제라, 시작 시각으로만
     * 거르면 오늘 조회에서 사라진다. 그 시간에 배치가 들어가는 것이 정확히 이 테이블이
     * 막으려던 일이다. 반열린 구간으로 본다: start_at &lt; rangeEnd AND end_at &gt; rangeStart.
     *
     * @param rangeStart 창의 시작(포함)
     * @param rangeEnd   창의 끝(제외). 마지막 날의 다음 날 자정을 넘긴다
     */
    List<Commitment> findOverlapping(@Param("userId") Long userId,
                                     @Param("rangeStart") LocalDateTime rangeStart,
                                     @Param("rangeEnd") LocalDateTime rangeEnd);

    /**
     * {@link #findOverlapping}의 잠금 조회. 계획 확정이 "미리보기 이후 약속이 생기지 않았나"를
     * 검사할 때 쓴다.
     *
     * <p>일반 조회는 트랜잭션이 처음 읽은 시점의 스냅샷을 보므로, 미리보기와 확정 사이에
     * 커밋된 약속을 놓칠 수 있다. FOR UPDATE는 항상 최신 커밋을 읽고, 아직 커밋되지 않은
     * 겹치는 INSERT가 있으면 그 트랜잭션이 끝날 때까지 기다린다 — 그래서 확정은 "검사할 때는
     * 없었는데 커밋 직후에 생긴" 약속과 겹친 채 성립하지 않는다.
     */
    List<Commitment> findOverlappingForUpdate(@Param("userId") Long userId,
                                              @Param("rangeStart") LocalDateTime rangeStart,
                                              @Param("rangeEnd") LocalDateTime rangeEnd);

    /**
     * 전체 교체 + 버전 증가. 갱신된 행 수가 1이 아니면 그 사이 누가 먼저 바꾼 것이다.
     *
     * <p>locationText는 COALESCE하지 않는다 — null이면 비운다. 수정이 PUT(전체 교체)이라
     * "생략"과 "비우기"를 구분할 필요가 없다.
     */
    int updateWithVersion(@Param("commitmentId") Long commitmentId,
                          @Param("userId") Long userId,
                          @Param("version") Long version,
                          @Param("title") String title,
                          @Param("startAt") LocalDateTime startAt,
                          @Param("endAt") LocalDateTime endAt,
                          @Param("locationText") String locationText);

    /**
     * 소프트 삭제. 행을 지우지 않는다 — 잘못 지운 것을 되살릴 수 있어야 한다.
     *
     * <p>파생 이동이면 원본 참조를 NULL로 만든다. uk_commitments_derived를 계속 점유하면
     * 사용자가 이동 블록을 지우고 같은 근무에 다시 만들 수 없다. 지워진 행의 원본 참조는
     * 보존 가치가 없다.
     */
    int softDeleteWithVersion(@Param("commitmentId") Long commitmentId,
                              @Param("userId") Long userId,
                              @Param("version") Long version);

    /**
     * 적용 직전에 원본 근무 행을 <b>FOR UPDATE로</b> 잡는다.
     *
     * <p>{@link #findByIdAndUserId}로 읽고 검사하면, 검사와 INSERT 사이에 다른 트랜잭션이
     * 근무 시각을 바꿔 커밋할 수 있다. 그러면 "검사할 때는 맞았던" 값으로 이동이 저장되고,
     * 사용자는 근무와 어긋난 블록을 보게 된다. 잠금은 커밋까지 유지된다.
     *
     * <p>이 경로 전용이다 — 일반 조회까지 잠금으로 바꾸면 상담·목록 조회가 서로를 기다린다.
     */
    Commitment findByIdAndUserIdForUpdate(@Param("commitmentId") Long commitmentId,
                                          @Param("userId") Long userId);

    /**
     * 근무 후보 조회. 조건은 겹침이 아니라 <b>"시작 날짜가 기간 안"</b>이다.
     *
     * <p>{@link #findOverlapping}과 목적이 다르다. 그쪽은 가용시간 계산용이라 "이 창에서 쓸 수
     * 없는 시간"을 찾고, 전날 밤에 시작해 오늘 새벽에 끝나는 약속도 오늘에 포함해야 한다.
     * 여기는 "이번 주에 시작하는 근무"를 고르는 것이라 그 약속은 이번 주가 아니다. 두 의미를
     * 한 쿼리로 겸하면 한쪽을 고칠 때 다른 쪽이 조용히 바뀐다.
     *
     * @param rangeStart 첫 날 00:00(포함)
     * @param rangeEnd   마지막 날 다음 날 00:00(제외)
     */
    List<Commitment> findWorkShiftCandidates(@Param("userId") Long userId,
                                             @Param("rangeStart") LocalDateTime rangeStart,
                                             @Param("rangeEnd") LocalDateTime rangeEnd);

    /**
     * 이 원본 근무들에서 파생된 살아 있는 이동 블록.
     *
     * <p>이동 자체의 날짜로 찾지 않는다. 마지막 날 근무의 이동은 다음 날로 넘어가 조회 기간
     * 밖이고, 사용자가 이동 블록의 날짜를 옮겼을 수도 있다. 그렇게 놓치면 "이미 있는데 없다"고
     * 판단해 같은 블록을 하나 더 만든다. 기준은 원본 근무 id다.
     */
    List<Commitment> findDerivedByOrigins(@Param("userId") Long userId,
                                          @Param("originIds") java.util.Collection<Long> originIds);

    /**
     * 같은 원본 근무에서 같은 방향으로 파생된 살아 있는 이동 블록. 적용 직전에
     * <b>FOR UPDATE로</b> 잡는다.
     *
     * <p>조회 후 검사만으로는 두 요청이 동시에 "없음"을 보고 둘 다 만든다. 인덱스
     * (user_id, derived_from_commitment_id, derived_relation)를 탄 FOR UPDATE는 행이 없어도
     * 그 자리를 잠그므로, 뒤에 온 쪽이 앞선 트랜잭션이 끝날 때까지 기다린다. unique 제약이
     * 최종 방어선이고 이 조회는 사용자에게 이유를 말해 주기 위한 것이다.
     */
    Commitment findDerivedForUpdate(@Param("userId") Long userId,
                                    @Param("originCommitmentId") Long originCommitmentId,
                                    @Param("relation") String relation);

}
