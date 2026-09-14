package com.jungwoo.project.memo.plan.selection;

import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.MaterialTextUnitMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 모델이 고른 구간을 <b>지금 상태로</b> 다시 확인하고, 저장된 추출 단위(페이지·슬라이드)에서 그 구간의 원문을 읽는다.
 *
 * <p>목록에서 보여 준 짧은 설명을 다시 쓰지 않는다 — 그러면 "원문을 읽었다"가 거짓이 된다. 원문 단위가 없는 옛 자료만
 * 분석 때 저장한 발췌로 대신하고 그 사실({@link Outcome#EXCERPT_ONLY})을 남긴다.
 *
 * <p>선택과 조회 사이에 바뀐 것은 섞지 않는다: 다른 사용자의 구간·삭제된 자료는 {@link Outcome#DROPPED_DELETED},
 * 파일이 바뀌어 해시가 다르면 {@link Outcome#DROPPED_CHANGED}, 프로젝트 연결이 끊겼거나 지정 자료가 아니면
 * {@link Outcome#DROPPED_SCOPE}. 떨어진 구간은 계획 호출에 들어가지 않고 결과에 이유와 함께 남는다.
 */
@Component
@RequiredArgsConstructor
public class PlanMaterialRetriever {

    private final MaterialSectionMapper sectionMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialTextUnitMapper textUnitMapper;
    private final MaterialLinkMapper materialLinkMapper;

    public enum Outcome {
        FULL, PARTIAL, EXCERPT_ONLY, DROPPED_DELETED, DROPPED_CHANGED, DROPPED_SCOPE, NOT_RETRIEVED_BUDGET;

        public boolean retrieved() {
            return this == FULL || this == PARTIAL || this == EXCERPT_ONLY;
        }
    }

    /**
     * 조회 대상 하나.
     *
     * @param expectedHash    선택 호출에 보여 준 구간의 파일 해시
     * @param courseId        그 구간이 속한 카탈로그의 프로젝트(프로젝트 없는 지정 자료면 null)
     * @param topicId         그 구간이 실린 학습 항목(스냅샷의 parentSourceId). 미연결이면 null
     */
    public record Target(String handle, Long sectionId, String expectedHash, Long courseId, Long topicId,
                         String reason) {
    }

    public record UnitText(int unitNo, TextUnitType type, String text) {
    }

    /** 조회 결과. units는 구간 범위 안의 원문 단위 전부(자르기 전). */
    public record Retrieved(Target target, MaterialSection section, CourseMaterial material, List<UnitText> units,
                            Outcome outcome) {

        public int fullChars() {
            if (outcome == Outcome.EXCERPT_ONLY) {
                return section.getExcerpt() == null ? 0 : section.getExcerpt().length();
            }
            return units.stream().mapToInt(u -> u.text().length()).sum();
        }
    }

    /** 프롬프트에 실을 형태. */
    public record Rendered(String text, String rangeLabel, Outcome outcome, int chars, String textHash) {
    }

    /**
     * @param scopeCourseIds       이번 계획의 대상 프로젝트
     * @param requestedMaterialIds 이번 요청에서 지정한 자료(프로젝트 없는 지정 자료의 범위 검사)
     */
    public List<Retrieved> retrieve(Long userId, List<Target> targets, Set<Long> scopeCourseIds,
                                    Set<Long> requestedMaterialIds) {
        if (targets.isEmpty()) {
            return List.of();
        }
        Map<Long, MaterialSection> sections = sectionMapper.findByIdsAndUserId(
                        targets.stream().map(Target::sectionId).distinct().toList(), userId).stream()
                .collect(Collectors.toMap(MaterialSection::getSectionId, Function.identity(), (a, b) -> a));
        Map<Long, CourseMaterial> materials = new HashMap<>();
        Map<String, List<MaterialTextUnit>> unitsByMaterialHash = new HashMap<>();
        List<Retrieved> out = new ArrayList<>();
        for (Target target : targets) {
            MaterialSection section = sections.get(target.sectionId());
            if (section == null || !Objects.equals(section.getUserId(), userId)
                    || !"ACTIVE".equals(section.getStatus()) || section.getExcerpt() == null) {
                out.add(new Retrieved(target, section, null, List.of(), Outcome.DROPPED_DELETED));
                continue;
            }
            CourseMaterial material = materials.computeIfAbsent(section.getMaterialId(),
                    id -> courseMaterialMapper.findByIdAndUserId(id, userId));
            if (material == null) {
                out.add(new Retrieved(target, section, null, List.of(), Outcome.DROPPED_DELETED));
                continue;
            }
            if (!Objects.equals(section.getFileHash(), target.expectedHash())
                    || !Objects.equals(material.getFileHash(), section.getFileHash())) {
                out.add(new Retrieved(target, section, material, List.of(), Outcome.DROPPED_CHANGED));
                continue;
            }
            boolean inScope = target.courseId() == null
                    ? requestedMaterialIds.contains(material.getMaterialId())
                    : scopeCourseIds.contains(target.courseId()) && (requestedMaterialIds.contains(material.getMaterialId())
                    || materialLinkMapper.findByMaterialIdAndCourseIdAndUserId(material.getMaterialId(), target.courseId(), userId) != null);
            if (!inScope) {
                out.add(new Retrieved(target, section, material, List.of(), Outcome.DROPPED_SCOPE));
                continue;
            }
            List<MaterialTextUnit> all = unitsByMaterialHash.computeIfAbsent(material.getMaterialId() + ":" + material.getFileHash(),
                    k -> textUnitMapper.findByMaterialIdAndHash(material.getMaterialId(), material.getFileHash()));
            int start = section.getUnitStart() == null ? Integer.MIN_VALUE : section.getUnitStart();
            int end = section.getUnitEnd() == null ? start : section.getUnitEnd();
            List<UnitText> units = all.stream()
                    .filter(u -> u.getUnitNo() != null && u.getUnitNo() >= start && u.getUnitNo() <= end)
                    .filter(u -> section.getUnitType() == null || u.getUnitType() == null || u.getUnitType() == section.getUnitType())
                    .filter(u -> u.getText() != null && !u.getText().isBlank())
                    .map(u -> new UnitText(u.getUnitNo(), u.getUnitType(), u.getText()))
                    .toList();
            out.add(new Retrieved(target, section, material, units, units.isEmpty() ? Outcome.EXCERPT_ONLY : Outcome.FULL));
        }
        return out;
    }

    /**
     * 조회한 원문을 글자 상한 안에서 프롬프트 형태로 만든다. 자르면 어디까지 실었는지를 범위 문장으로 남긴다
     * ("p.3 전체, p.4 앞 600자 (구간 p.3~5, 원문 4,210자 중 1,800자)").
     */
    public static Rendered render(Retrieved retrieved, int maxChars) {
        MaterialSection section = retrieved.section();
        String locator = section.locator();
        if (retrieved.outcome() == Outcome.EXCERPT_ONLY) {
            String excerpt = flat(section.getExcerpt());
            String text = excerpt.length() <= maxChars ? excerpt : excerpt.substring(0, maxChars) + "…";
            return new Rendered(text, "원문 단위가 없는 자료 — 분석 때 저장한 발췌만" + (locator.isBlank() ? "" : " (" + locator + ")"),
                    Outcome.EXCERPT_ONLY, text.length(), hash(text));
        }
        StringBuilder sb = new StringBuilder();
        List<String> parts = new ArrayList<>();
        int used = 0;
        int total = retrieved.fullChars();
        boolean cut = false;
        for (UnitText unit : retrieved.units()) {
            String label = (unit.type() == null ? "" : unit.type().label()) + unit.unitNo();
            String body = unit.text().strip();
            String head = "[" + label + "]\n";
            int room = maxChars - used - head.length();
            if (room <= 0) {
                cut = true;
                break;
            }
            if (body.length() <= room) {
                sb.append(head).append(body).append('\n');
                used += head.length() + body.length() + 1;
                parts.add(label + " 전체");
            } else {
                sb.append(head).append(body, 0, room).append("…\n");
                used += head.length() + room + 2;
                parts.add(label + " 앞 " + String.format("%,d", room) + "자");
                cut = true;
                break;
            }
        }
        String text = sb.toString().strip();
        String range;
        if (!cut) {
            range = (locator.isBlank() ? "" : locator + " ") + "원문 전체(" + String.format("%,d", total) + "자)";
        } else {
            range = String.join(", ", parts) + " (구간 " + locator + ", 원문 " + String.format("%,d", total)
                    + "자 중 " + String.format("%,d", text.length()) + "자)";
        }
        return new Rendered(text, range, cut ? Outcome.PARTIAL : Outcome.FULL, text.length(), hash(text));
    }

    private static String flat(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }
}
