package com.jungwoo.project.memo.commitment.ai;

import com.jungwoo.project.memo.ai.change.ChangeCheck;
import com.jungwoo.project.memo.ai.change.ChangeModule;
import com.jungwoo.project.memo.ai.change.ChangeOp;
import com.jungwoo.project.memo.commitment.CommitmentMapper;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.commitment.dto.CommitmentCreateRequest;
import com.jungwoo.project.memo.commitment.dto.CommitmentUpdateRequest;
import com.jungwoo.project.memo.scheduling.scenario.Scenario;
import com.jungwoo.project.memo.scheduling.scenario.ScenarioProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 약속(일회성 일정)의 상담 변경: 만들기·고치기·취소. 규칙은 {@link CommitmentService}에 있다(시간 역전, 분 단위, version).
 *
 * <p>근무 약속을 취소하면 저장된 파생 이동(derived_from_commitment_id로 연결된 이동 약속)을 함께 고를 항목으로 낸다 — 저장된 연결이라
 * 기본 선택이다. 원본을 지워도 파생 이동은 자동으로 따라가지 않는 기존 정책을 바꾸지 않고, 사용자가 고르게 한다.
 */
@Component
@RequiredArgsConstructor
public class CommitmentChangeModule implements ChangeModule {

    private final CommitmentMapper commitmentMapper;
    private final CommitmentService commitmentService;

    public record CreateCommitment(String title, LocalDateTime startAt, LocalDateTime endAt, String locationText)
            implements ChangeOp, ScenarioProjection {
        public String type() {
            return "COMMITMENT_CREATE";
        }

        public String targetKey() {
            return "commitment:new:" + title + ":" + startAt;
        }

        public String summary() {
            return "약속 만들기: " + title + " " + startAt.toLocalDate() + " " + startAt.toLocalTime() + "~" + endAt.toLocalTime();
        }

        public List<LocalDate> affectedDates() {
            return List.of(startAt.toLocalDate(), endAt.toLocalDate());
        }

        public void project(Scenario.Builder s) {
            s.commitments().put(Commitment.builder().commitmentId(s.nextTempId()).title(title)
                    .startAt(startAt).endAt(endAt).locationText(locationText).isDeleted(false).build());
        }
    }

    public record UpdateCommitment(Long commitmentId, Long version, String title, LocalDateTime startAt, LocalDateTime endAt,
                                   String locationText, LocalDateTime beforeStartAt, LocalDateTime beforeEndAt)
            implements ChangeOp, ScenarioProjection {
        public String type() {
            return "COMMITMENT_UPDATE";
        }

        public String targetKey() {
            return "commitment:" + commitmentId;
        }

        public String summary() {
            return "약속 고치기: " + title + " → " + startAt.toLocalDate() + " " + startAt.toLocalTime() + "~" + endAt.toLocalTime();
        }

        public List<LocalDate> affectedDates() {
            List<LocalDate> out = new ArrayList<>(List.of(startAt.toLocalDate(), endAt.toLocalDate()));
            if (beforeStartAt != null) {
                out.add(beforeStartAt.toLocalDate());
            }
            if (beforeEndAt != null) {
                out.add(beforeEndAt.toLocalDate());
            }
            return out;
        }

        public void project(Scenario.Builder s) {
            s.commitments().put(Commitment.builder().commitmentId(commitmentId).title(title).startAt(startAt).endAt(endAt)
                    .locationText(locationText).isDeleted(false).build());
        }
    }

    public record CancelCommitment(Long commitmentId, Long version, String title, LocalDateTime startAt, LocalDateTime endAt)
            implements ChangeOp, ScenarioProjection {
        public String type() {
            return "COMMITMENT_CANCEL";
        }

        public String targetKey() {
            return "commitment:" + commitmentId;
        }

        public String summary() {
            return "약속 취소: " + title + " " + startAt.toLocalDate() + " " + startAt.toLocalTime() + "~" + endAt.toLocalTime();
        }

        public List<LocalDate> affectedDates() {
            return List.of(startAt.toLocalDate(), endAt.toLocalDate());
        }

        public void project(Scenario.Builder s) {
            s.commitments().put(Commitment.builder().commitmentId(commitmentId).title(title).startAt(startAt).endAt(endAt)
                    .isDeleted(true).build());
        }
    }

    @Override
    public Map<String, Class<? extends ChangeOp>> opTypes() {
        return Map.of("COMMITMENT_CREATE", CreateCommitment.class, "COMMITMENT_UPDATE", UpdateCommitment.class,
                "COMMITMENT_CANCEL", CancelCommitment.class);
    }

    @Override
    public Map<String, String> toolArgs() {
        return Map.of(
                "COMMITMENT_CREATE", "{title, startAt, endAt, locationText?} - 한 번만 있는 약속(YYYY-MM-DDTHH:mm)",
                "COMMITMENT_UPDATE", "{commitmentId, startAt, endAt, title?, locationText?} - 약속 시각/제목 고치기",
                "COMMITMENT_CANCEL", "{commitmentId} - 약속 취소");
    }

    @Override
    public ChangeOp fromToolArgs(String type, com.fasterxml.jackson.databind.JsonNode a) {
        return switch (type) {
            case "COMMITMENT_CREATE" -> new CreateCommitment(com.jungwoo.project.memo.ai.change.ToolArgs.text(a, "title"), com.jungwoo.project.memo.ai.change.ToolArgs.dateTime(a, "startAt"),
                    com.jungwoo.project.memo.ai.change.ToolArgs.dateTime(a, "endAt"), com.jungwoo.project.memo.ai.change.ToolArgs.text(a, "locationText"));
            case "COMMITMENT_UPDATE" -> new UpdateCommitment(com.jungwoo.project.memo.ai.change.ToolArgs.id(a, "commitmentId"), null, com.jungwoo.project.memo.ai.change.ToolArgs.text(a, "title"),
                    com.jungwoo.project.memo.ai.change.ToolArgs.dateTime(a, "startAt"), com.jungwoo.project.memo.ai.change.ToolArgs.dateTime(a, "endAt"), com.jungwoo.project.memo.ai.change.ToolArgs.text(a, "locationText"), null, null);
            case "COMMITMENT_CANCEL" -> new CancelCommitment(com.jungwoo.project.memo.ai.change.ToolArgs.id(a, "commitmentId"), null, null, null, null);
            default -> throw new IllegalArgumentException("모르는 변경: " + type);
        };
    }

    @Override
    public int lockOrder() {
        return 10;
    }

    @Override
    public ChangeCheck check(Long userId, ChangeOp op) {
        return switch (op) {
            case CreateCommitment c -> c.title() == null || c.title().isBlank() || c.startAt() == null || c.endAt() == null
                    || !c.endAt().isAfter(c.startAt())
                    ? ChangeCheck.fail(ChangeCheck.INVALID, "제목과 시작·끝(끝이 뒤)이 필요해요", op) : ChangeCheck.ok(op);
            case UpdateCommitment u -> {
                Commitment current = commitmentMapper.findByIdAndUserId(u.commitmentId(), userId);
                if (current == null) {
                    yield ChangeCheck.fail(ChangeCheck.NOT_FOUND, "그 약속을 찾지 못했어요", op);
                }
                if (u.startAt() == null || u.endAt() == null || !u.endAt().isAfter(u.startAt())) {
                    yield ChangeCheck.fail(ChangeCheck.INVALID, "시작·끝이 맞지 않아요", op);
                }
                yield ChangeCheck.ok(new UpdateCommitment(current.getCommitmentId(), current.getVersion(),
                        u.title() == null ? current.getTitle() : u.title(), u.startAt(), u.endAt(),
                        u.locationText() == null ? current.getLocationText() : u.locationText(), current.getStartAt(),
                        current.getEndAt()));
            }
            case CancelCommitment c -> {
                Commitment current = commitmentMapper.findByIdAndUserId(c.commitmentId(), userId);
                if (current == null) {
                    yield ChangeCheck.fail(ChangeCheck.NOT_FOUND, "그 약속을 찾지 못했어요(이미 취소됐을 수 있어요)", op);
                }
                yield ChangeCheck.ok(new CancelCommitment(current.getCommitmentId(), current.getVersion(), current.getTitle(),
                        current.getStartAt(), current.getEndAt()));
            }
            default -> throw new IllegalArgumentException(op.type());
        };
    }

    @Override
    public void lock(Long userId, List<ChangeOp> ops) {
        ops.stream().map(this::targetId).filter(Objects::nonNull).sorted()
                .forEach(id -> commitmentMapper.findByIdAndUserIdForUpdate(id, userId));
    }

    @Override
    public ChangeCheck recheck(Long userId, ChangeOp op) {
        Long id = targetId(op);
        if (id == null) {
            return ChangeCheck.ok(op);
        }
        // 적용 트랜잭션 안 — 잠금 조회로 최신 커밋을 읽는다(일반 조회는 트랜잭션 스냅샷이다).
        Commitment current = commitmentMapper.findByIdAndUserIdForUpdate(id, userId);
        Long expected = op instanceof UpdateCommitment u ? u.version() : ((CancelCommitment) op).version();
        if (current == null) {
            return ChangeCheck.fail(ChangeCheck.STALE, "그 약속이 지워졌어요", op);
        }
        return Objects.equals(current.getVersion(), expected) ? ChangeCheck.ok(op)
                : ChangeCheck.fail(ChangeCheck.STALE, current.getTitle() + "이 검토한 뒤 바뀌었어요", op);
    }

    @Override
    public Map<String, Object> apply(Long userId, ChangeOp op) {
        return switch (op) {
            case CreateCommitment c -> Map.of("commitmentId", commitmentService.create(userId,
                    CommitmentCreateRequest.builder().title(c.title()).startAt(c.startAt()).endAt(c.endAt())
                            .locationText(c.locationText()).build(),
                    CommitmentSourceType.AI_SUGGESTION_APPROVED).getCommitmentId());
            case UpdateCommitment u -> Map.of("commitmentId", commitmentService.update(userId, u.commitmentId(),
                    CommitmentUpdateRequest.builder().title(u.title()).startAt(u.startAt()).endAt(u.endAt())
                            .locationText(u.locationText()).version(u.version()).build()).getCommitmentId());
            case CancelCommitment c -> {
                commitmentService.delete(userId, c.commitmentId(), c.version());
                yield Map.of("cancelledCommitmentId", c.commitmentId());
            }
            default -> throw new IllegalArgumentException(op.type());
        };
    }

    /** 근무 약속 취소 → 저장된 파생 이동도 함께 취소할 항목(기본 선택). */
    @Override
    public List<LinkedChange> linked(Long userId, ChangeOp op) {
        if (!(op instanceof CancelCommitment cancel)) {
            return List.of();
        }
        List<LinkedChange> out = new ArrayList<>();
        for (Commitment travel : commitmentService.findDerivedByOrigins(userId, List.of(cancel.commitmentId()))) {
            out.add(new LinkedChange(new CancelCommitment(travel.getCommitmentId(), travel.getVersion(), travel.getTitle(),
                    travel.getStartAt(), travel.getEndAt()), true, "이 근무에 연결된 이동이에요"));
        }
        return out;
    }

    private Long targetId(ChangeOp op) {
        return switch (op) {
            case UpdateCommitment u -> u.commitmentId();
            case CancelCommitment c -> c.commitmentId();
            default -> null;
        };
    }
}
