package com.jungwoo.project.memo.commitment;

import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.commitment.dto.CommitmentCreateRequest;
import com.jungwoo.project.memo.commitment.dto.CommitmentResponse;
import com.jungwoo.project.memo.commitment.dto.CommitmentUpdateRequest;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 약속 CRUD의 검증과 소유권·버전 처리.
 *
 * <p>여기서 지키는 선 둘: 출처를 클라이언트가 정하지 못한다는 것과, 길이 0/역전 구간이
 * 저장 경로에 들어가지 못한다는 것.
 */
@ExtendWith(MockitoExtension.class)
class CommitmentServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long COMMITMENT_ID = 30L;

    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 4, 19, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 9, 4, 21, 0);

    @Mock
    private CommitmentMapper commitmentMapper;

    @InjectMocks
    private CommitmentService commitmentService;

    private CommitmentCreateRequest createRequest(LocalDateTime startAt, LocalDateTime endAt) {
        return CommitmentCreateRequest.builder()
                .title("친구 약속")
                .startAt(startAt)
                .endAt(endAt)
                .locationText("홍대")
                .build();
    }

    private Commitment stored() {
        return Commitment.builder()
                .commitmentId(COMMITMENT_ID)
                .userId(USER_ID)
                .title("친구 약속")
                .startAt(START)
                .endAt(END)
                .locationText("홍대")
                .sourceType(CommitmentSourceType.MANUAL)
                .version(3L)
                .isDeleted(false)
                .build();
    }

    // ===== 생성 =====

    @Test
    void 만들면_요청값_그대로_저장한다() {
        CommitmentResponse response =
                commitmentService.create(USER_ID, createRequest(START, END), CommitmentSourceType.MANUAL);

        ArgumentCaptor<Commitment> captor = ArgumentCaptor.forClass(Commitment.class);
        verify(commitmentMapper).insert(captor.capture());
        Commitment saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getTitle()).isEqualTo("친구 약속");
        assertThat(saved.getStartAt()).isEqualTo(START);
        assertThat(saved.getEndAt()).isEqualTo(END);
        assertThat(saved.getLocationText()).isEqualTo("홍대");
        assertThat(response.getVersion()).isZero();
    }

    @Test
    void 출처는_부르는_쪽이_정하고_요청에는_그_자리가_없다() {
        commitmentService.create(USER_ID, createRequest(START, END),
                CommitmentSourceType.AI_SUGGESTION_APPROVED);

        ArgumentCaptor<Commitment> captor = ArgumentCaptor.forClass(Commitment.class);
        verify(commitmentMapper).insert(captor.capture());
        assertThat(captor.getValue().getSourceType())
                .isEqualTo(CommitmentSourceType.AI_SUGGESTION_APPROVED);
        // CommitmentCreateRequest에 sourceType 필드 자체가 없다 — 위조할 자리를 만들지 않는다.
        assertThat(CommitmentCreateRequest.class.getDeclaredFields())
                .noneMatch(f -> f.getName().equals("sourceType"));
    }

    @Test
    void 장소를_비워도_유효하고_공백은_null이_된다() {
        CommitmentCreateRequest request = createRequest(START, END);
        request.setLocationText("   ");

        commitmentService.create(USER_ID, request, CommitmentSourceType.MANUAL);

        ArgumentCaptor<Commitment> captor = ArgumentCaptor.forClass(Commitment.class);
        verify(commitmentMapper).insert(captor.capture());
        assertThat(captor.getValue().getLocationText()).isNull();
    }

    @Test
    void 자정을_넘겨도_그냥_두_시각이다() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 4, 22, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 5, 2, 0);

        commitmentService.create(USER_ID, createRequest(start, end), CommitmentSourceType.MANUAL);

        ArgumentCaptor<Commitment> captor = ArgumentCaptor.forClass(Commitment.class);
        verify(commitmentMapper).insert(captor.capture());
        // 루틴처럼 end <= start를 다음 날로 읽는 추론이 필요 없다.
        assertThat(captor.getValue().getEndAt()).isEqualTo(end);
    }

    @Test
    void 시작과_종료가_같으면_거부한다() {
        assertThatThrownBy(() ->
                commitmentService.create(USER_ID, createRequest(START, START), CommitmentSourceType.MANUAL))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TIME_RANGE);
        verify(commitmentMapper, never()).insert(any());
    }

    @Test
    void 종료가_시작보다_이르면_거부한다() {
        assertThatThrownBy(() ->
                commitmentService.create(USER_ID, createRequest(END, START), CommitmentSourceType.MANUAL))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TIME_RANGE);
        verify(commitmentMapper, never()).insert(any());
    }

    @Test
    void 초가_섞인_시각은_거부한다() {
        // 30초짜리 약속을 만들 수 있으면 화면에 보이는 값과 저장된 값이 어긋난다.
        assertThatThrownBy(() -> commitmentService.create(
                USER_ID, createRequest(START.withSecond(30), END), CommitmentSourceType.MANUAL))
                .isInstanceOf(BadRequestException.class);
        verify(commitmentMapper, never()).insert(any());
    }

    // ===== 조회 =====

    @Test
    void 기간_조회는_마지막_날_끝까지_포함하는_반열린_구간으로_묻는다() {
        when(commitmentMapper.findOverlapping(anyLong(), any(), any())).thenReturn(List.of());

        commitmentService.list(USER_ID, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7));

        // 마지막 날 23:00에 시작하는 약속이 빠지면 안 되므로 끝은 다음 날 자정이다.
        verify(commitmentMapper).findOverlapping(
                eq(USER_ID),
                eq(LocalDateTime.of(2026, 9, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 8, 0, 0)));
    }

    @Test
    void 뒤집힌_범위는_조회하지_않는다() {
        assertThat(commitmentService.list(USER_ID, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 1)))
                .isEmpty();
        verify(commitmentMapper, never()).findOverlapping(anyLong(), any(), any());
    }

    // ===== 수정 =====

    @Test
    void 수정은_전체_교체이고_버전이_오른다() {
        when(commitmentMapper.findByIdAndUserId(COMMITMENT_ID, USER_ID)).thenReturn(stored());
        when(commitmentMapper.updateWithVersion(anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(1);

        CommitmentResponse response = commitmentService.update(USER_ID, COMMITMENT_ID,
                CommitmentUpdateRequest.builder()
                        .title("친구 약속")
                        .startAt(START)
                        .endAt(LocalDateTime.of(2026, 9, 4, 21, 30))
                        .locationText(null)
                        .version(3L)
                        .build());

        assertThat(response.getVersion()).isEqualTo(4L);
        assertThat(response.getEndAt()).isEqualTo(LocalDateTime.of(2026, 9, 4, 21, 30));
        // PUT이므로 비운 장소는 비워진다.
        assertThat(response.getLocationText()).isNull();
    }

    @Test
    void 그_사이_바뀌었으면_409다() {
        when(commitmentMapper.findByIdAndUserId(COMMITMENT_ID, USER_ID)).thenReturn(stored());
        when(commitmentMapper.updateWithVersion(anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> commitmentService.update(USER_ID, COMMITMENT_ID,
                CommitmentUpdateRequest.builder()
                        .title("친구 약속").startAt(START).endAt(END).version(1L).build()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VERSION_CONFLICT);
    }

    @Test
    void 남의_약속은_없는_것으로_본다() {
        when(commitmentMapper.findByIdAndUserId(COMMITMENT_ID, OTHER_USER_ID)).thenReturn(null);

        // 403이면 "그 id는 존재한다"를 알려주는 셈이다.
        assertThatThrownBy(() -> commitmentService.update(OTHER_USER_ID, COMMITMENT_ID,
                CommitmentUpdateRequest.builder()
                        .title("친구 약속").startAt(START).endAt(END).version(0L).build()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COMMITMENT_NOT_FOUND);
    }

    @Test
    void 수정에서도_역전_구간은_거부하고_쓰지_않는다() {
        when(commitmentMapper.findByIdAndUserId(COMMITMENT_ID, USER_ID)).thenReturn(stored());

        assertThatThrownBy(() -> commitmentService.update(USER_ID, COMMITMENT_ID,
                CommitmentUpdateRequest.builder()
                        .title("친구 약속").startAt(END).endAt(START).version(3L).build()))
                .isInstanceOf(BadRequestException.class);
        verify(commitmentMapper, never())
                .updateWithVersion(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
    }

    // ===== 삭제 =====

    @Test
    void 삭제는_소프트_삭제이고_버전을_본다() {
        when(commitmentMapper.findByIdAndUserId(COMMITMENT_ID, USER_ID)).thenReturn(stored());
        when(commitmentMapper.softDeleteWithVersion(COMMITMENT_ID, USER_ID, 3L)).thenReturn(1);

        commitmentService.delete(USER_ID, COMMITMENT_ID, 3L);

        verify(commitmentMapper).softDeleteWithVersion(COMMITMENT_ID, USER_ID, 3L);
    }

    @Test
    void 삭제_버전이_어긋나면_409다() {
        when(commitmentMapper.findByIdAndUserId(COMMITMENT_ID, USER_ID)).thenReturn(stored());
        when(commitmentMapper.softDeleteWithVersion(COMMITMENT_ID, USER_ID, 1L)).thenReturn(0);

        assertThatThrownBy(() -> commitmentService.delete(USER_ID, COMMITMENT_ID, 1L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VERSION_CONFLICT);
    }

    // ===== 완료 대상이 아니다 =====

    @Test
    void 완료나_진행률에_해당하는_것이_응답에_없다() {
        // 필드가 있으면 언젠가 화면이 그것으로 완료 버튼을 그린다.
        assertThat(CommitmentResponse.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("status", "completionPercent", "actualMinutes", "expectedMinutes");
    }
}
