package com.jungwoo.project.memo.plan.generation;

import com.jungwoo.project.memo.plan.domain.ProjectOutcome;
import com.jungwoo.project.memo.plan.domain.ProjectOutcome.Disposition;
import com.jungwoo.project.memo.plan.domain.ProjectOutcome.MaterialState;
import com.jungwoo.project.memo.plan.domain.ProjectOutcome.NextAction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 대상 프로젝트별 처리 결과를 완성한다. 입력은 (1) 서버가 센 사실과 (2) 모델이 낸 판단이고, 출력에는 대상 프로젝트가
 * <b>모두 정확히 한 번</b> 나온다.
 *
 * <p>서버가 정하는 것과 정하지 않는 것:
 * <ul>
 *   <li>자료 상태({@link MaterialState})는 전부 서버 사실이다 — 모델의 말로 바꾸지 않는다.</li>
 *   <li>항목이 실제로 있으면 INCLUDED다(모델이 뭐라고 했든). 모델이 INCLUDED라고 했는데 항목이 없으면 UNDECIDED로 낮춘다.</li>
 *   <li>모델이 "제외"라고 했지만 후보를 한 줄도 보지 못했거나 원문 조회가 실패한 프로젝트는 NOT_REVIEWED다 — 보지 못한 것을
 *       중요도 판단으로 포장하지 않는다. 단, 자료가 없거나 사용자 지시가 근거인 제외는 볼 자료가 없어도 성립한다.</li>
 *   <li>모델이 결과를 내지 않은 프로젝트는 NOT_REVIEWED다. 서버는 제외 이유나 학습 항목을 만들어 채우지 않는다.</li>
 *   <li>범위 밖 id·중복은 버린다.</li>
 * </ul>
 */
public final class ProjectOutcomeResolver {

    static final int MAX_REASON = 300;

    private ProjectOutcomeResolver() {
    }

    /**
     * 서버가 센 프로젝트 하나의 사실.
     *
     * @param shown            선택 모델 입력에 줄로 실린 후보 수. 선택을 재사용했으면 null
     * @param retrievalFailed  고른 구간 중 원문을 싣지 못한 수
     * @param selectionReason  선택 모델이 남긴 판단 이유(SKIPPED/UNSURE일 때 참고)
     */
    public record Facts(Long courseId, String courseTitle, int materials, int pendingMaterials, int candidates,
                        Integer shown, int selected, int delivered, int retrievalFailed, List<Long> deliveredSectionIds,
                        String selectionDecision, String selectionReason,
                        /** 줄은 0이지만 접힌 묶음 요약(개요)은 보여 줬고, 모델이 펼치지 않기로 했다. */
                        boolean outlineOnlyByChoice) {
    }

    /** 모델이 낸 프로젝트 판단 하나(검증 전). */
    public record ModelOut(Long courseId, String disposition, String reason, String nextAction) {
    }

    public record ItemTotals(int count, int minutes) {
    }

    public static List<ProjectOutcome> resolve(List<Facts> targets, List<ModelOut> modelOuts,
                                               Map<Long, ItemTotals> itemsByCourse) {
        Map<Long, ModelOut> byCourse = new LinkedHashMap<>();
        Set<Long> targetIds = new HashSet<>();
        targets.forEach(t -> targetIds.add(t.courseId()));
        for (ModelOut out : modelOuts == null ? List.<ModelOut>of() : modelOuts) {
            if (out == null || out.courseId() == null || !targetIds.contains(out.courseId())) {
                continue; // 다른 소유자·범위 밖 id는 받지 않는다.
            }
            byCourse.putIfAbsent(out.courseId(), out); // 중복은 첫 번째만.
        }

        List<ProjectOutcome> result = new ArrayList<>();
        for (Facts f : targets) {
            MaterialState state = materialState(f);
            ItemTotals totals = itemsByCourse.getOrDefault(f.courseId(), new ItemTotals(0, 0));
            ModelOut out = byCourse.get(f.courseId());
            String modelDisposition = out == null || out.disposition() == null ? null
                    : out.disposition().trim().toUpperCase(Locale.ROOT);
            String modelReason = out == null ? null : cut(out.reason());

            Disposition disposition;
            String reason;
            String decidedBy = "MODEL";
            if (totals.count() > 0) {
                disposition = Disposition.INCLUDED;
                reason = modelReason;
            } else if (out == null) {
                disposition = Disposition.NOT_REVIEWED;
                decidedBy = "SERVER";
                reason = serverReason(state, f, "이번 생성에서 이 프로젝트의 검토 결과가 나오지 않았어요.");
            } else if ("EXCLUDED".equals(modelDisposition) || "EXCLUDED_BY_CHOICE".equals(modelDisposition)) {
                if (state == MaterialState.NOT_LISTED || state == MaterialState.RETRIEVAL_FAILED
                        || state == MaterialState.ANALYSIS_PENDING) {
                    disposition = Disposition.NOT_REVIEWED;
                    decidedBy = "SERVER";
                    reason = serverReason(state, f, null);
                } else {
                    disposition = Disposition.EXCLUDED_BY_CHOICE;
                    reason = modelReason;
                }
            } else if ("INCLUDED".equals(modelDisposition)) {
                // 포함이라고 했지만 항목이 없다(검증에서 버려졌거나 모델이 만들지 않았다).
                disposition = Disposition.UNDECIDED;
                decidedBy = "SERVER";
                reason = "이 프로젝트를 다루겠다고 했지만 이번 초안에 남은 항목이 없어요.";
            } else if (state == MaterialState.NOT_LISTED || state == MaterialState.RETRIEVAL_FAILED) {
                disposition = Disposition.NOT_REVIEWED;
                decidedBy = "SERVER";
                reason = serverReason(state, f, null);
            } else {
                disposition = Disposition.UNDECIDED;
                reason = modelReason != null ? modelReason : serverReason(state, f, "아직 정하지 못했어요.");
                if (modelReason == null) {
                    decidedBy = "SERVER";
                }
            }
            result.add(new ProjectOutcome(f.courseId(), f.courseTitle(), disposition, reason, decidedBy, state,
                    f.candidates(), f.shown(), f.selected(), f.delivered(), totals.count(), totals.minutes(),
                    f.deliveredSectionIds() == null ? List.of() : List.copyOf(f.deliveredSectionIds()),
                    nextAction(disposition, state, out)));
        }
        return result;
    }

    static MaterialState materialState(Facts f) {
        if (f.delivered() > 0) {
            return MaterialState.TEXT_DELIVERED;
        }
        if (f.selected() > 0 && f.retrievalFailed() > 0) {
            return MaterialState.RETRIEVAL_FAILED;
        }
        if (f.candidates() == 0) {
            if (f.materials() == 0) {
                return MaterialState.NO_MATERIAL;
            }
            return f.pendingMaterials() > 0 ? MaterialState.ANALYSIS_PENDING : MaterialState.NO_RELEVANT_CONTENT;
        }
        if (f.shown() != null && f.shown() == 0 && !f.outlineOnlyByChoice()) {
            return MaterialState.NOT_LISTED;
        }
        return MaterialState.OUTLINE_ONLY;
    }

    private static String serverReason(MaterialState state, Facts f, String fallback) {
        return switch (state) {
            case NO_MATERIAL -> "연결된 자료가 없어 검토할 내용이 없었어요.";
            case ANALYSIS_PENDING -> "자료 " + f.pendingMaterials() + "개의 분석이 끝나지 않아 아직 내용을 볼 수 없었어요.";
            case NO_RELEVANT_CONTENT -> "분석된 자료에서 계획에 쓸 구간을 찾지 못했어요.";
            case NOT_LISTED -> "입력 한도 때문에 이 프로젝트의 자료 목록(후보 " + f.candidates()
                    + "개)을 이번에는 보지 못했어요. 중요도를 판단한 것이 아니에요.";
            case RETRIEVAL_FAILED -> "고른 구간 " + f.retrievalFailed() + "개의 원문을 읽지 못했어요. 중요도를 판단한 것이 아니에요.";
            default -> fallback != null ? fallback : "이번에는 검토하지 못했어요.";
        };
    }

    private static NextAction nextAction(Disposition disposition, MaterialState state, ModelOut out) {
        if (disposition == Disposition.INCLUDED || disposition == Disposition.EXCLUDED_BY_CHOICE) {
            return null;
        }
        return switch (state) {
            case NO_MATERIAL -> NextAction.UPLOAD_MATERIAL;
            case ANALYSIS_PENDING -> NextAction.WAIT_ANALYSIS;
            case RETRIEVAL_FAILED -> NextAction.RETRY_ANALYSIS;
            case NOT_LISTED -> NextAction.NARROW_SCOPE;
            default -> {
                if (out != null && out.nextAction() != null) {
                    try {
                        yield NextAction.valueOf(out.nextAction().trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                        // 모르는 값은 기본값으로.
                    }
                }
                yield disposition == Disposition.UNDECIDED ? NextAction.ANSWER_QUESTION : NextAction.REVIEW_LATER;
            }
        };
    }

    private static String cut(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String flat = s.strip().replaceAll("\\s+", " ");
        return flat.length() > MAX_REASON ? flat.substring(0, MAX_REASON) : flat;
    }
}
