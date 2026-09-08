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

    /**
     * 기간 안에서 이미 만들어진 파생 이동의 (원본 id, 관계). 같은 요청을 다시 말했을 때
     * 같은 블록을 또 후보로 만들지 않기 위해 읽는다.
     */
    List<Commitment> findDerivedInRange(@Param("userId") Long userId,
                                        @Param("rangeStart") LocalDateTime rangeStart,
                                        @Param("rangeEnd") LocalDateTime rangeEnd);
}
