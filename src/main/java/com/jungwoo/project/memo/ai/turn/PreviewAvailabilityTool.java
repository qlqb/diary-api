package com.jungwoo.project.memo.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.change.ToolArgs;
import com.jungwoo.project.memo.plan.StudyTimeBudget;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.scenario.Scenario;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 남는 시간 계산 — 원본 또는 가정 상태(제안 목록을 반영)로. 말한 공부 시간 조건이 있으면 그 범위로 계산하고 요청량·들어갈 수 있는 양·
 * 겹친 일정을 따로 준다. 계획 생성기와 같은 계산(AvailabilityEstimateService·StudyTimeBudget)이다. DB를 바꾸지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PreviewAvailabilityTool implements ConsultTool {

    private final AvailabilityEstimateService availability;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Override
    public String name() {
        return "preview_availability";
    }

    @Override
    public String description() {
        return "기간의 남는 시간을 계산한다. withChanges=true면 제안 목록(선택된 것)을 반영한 가정 상태, false면 원본. set_study_time으로 정한 "
                + "조건이 있으면 그 범위·제외로 계산하고 요청량·들어갈 수 있는 양·부족분·겹친 일정을 준다. 충돌·부족의 실제 원인은 이 결과로 말한다.";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"},"withChanges":{"type":"boolean"}},
                 "required":["from","to","withChanges"],"additionalProperties":false}""";
    }

    @Override
    public String progressLabel() {
        return "남는 시간 계산 중";
    }

    @Override
    public String call(TurnWorkspace ws, JsonNode args) {
        LocalDate from = ToolArgs.date(args, "from");
        LocalDate to = ToolArgs.date(args, "to");
        if (to.isBefore(from) || to.isAfter(from.plusDays(13))) {
            throw new IllegalArgumentException("기간은 시작 이후 14일 안이어야 한다");
        }
        boolean withChanges = args.path("withChanges").asBoolean(false);
        Scenario scenario = ws.scenario(withChanges);
        Scenario.StudyWindows study = StudyTimeBudget.windowsFor(ws.condition(), from, to);
        AvailabilityEstimateResult r = AvailabilityEstimateService.estimateFor(availability, ws.userId(), from, to, List.of(),
                List.of(), scenario.withStudy(study));
        int free = 0;
        java.util.List<String> windows = new java.util.ArrayList<>();
        for (AvailabilityWindow w : r.windows()) {
            free += (int) Duration.between(w.startAt(), w.endAt()).toMinutes();
            windows.add(w.startAt().toLocalDate() + " " + hhmm(w.startAt().toLocalTime()) + "~" + hhmm(w.endAt().toLocalTime()));
        }
        java.util.List<String> busy = new java.util.ArrayList<>();
        for (BusyWindow b : r.busyWindows()) {
            busy.add(b.startAt().toLocalDate() + " " + hhmm(b.startAt().toLocalTime()) + "~" + hhmm(b.endAt().toLocalTime()) + " " + b.label());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", "OK");
        out.put("state", withChanges ? "가정(제안 반영, 적용 전)" : "원본");
        out.put("freeMinutes", free);
        out.put("windows", windows.size() > 30 ? windows.subList(0, 30) : windows);
        out.put("busy", busy.size() > 30 ? busy.subList(0, 30) : busy);
        if (ws.condition() != null) {
            int days = (int) java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
            StudyTimeBudget b = StudyTimeBudget.compute(ws.condition(), study, r.windows(), r.busyWindows(), days,
                    PlanIntensity.NORMAL);
            Map<String, Object> budget = new LinkedHashMap<>();
            budget.put("condition", ws.condition().label());
            budget.put("requestedMinutes", b.requestedMinutes());
            budget.put("capacityMinutes", b.capacityMinutes());
            budget.put("targetMinutes", b.targetMinutes());
            budget.put("breakReserveMinutes", b.breakReserveMinutes());
            budget.put("shortfallMinutes", b.shortfallMinutes());
            budget.put("conflicts", b.conflicts());
            out.put("studyTime", budget);
        }
        ws.checked("남는 시간 " + from + "~" + to + "(" + (withChanges ? "가정" : "원본") + ") " + free + "분");
        try {
            return json.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hhmm(LocalTime t) {
        return t.equals(LocalTime.MIDNIGHT) ? "24:00" : t.toString();
    }
}
