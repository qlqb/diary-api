package com.jungwoo.project.memo.plan.selection;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 이번 요청에서 사용자가 지정한 자료를 확정한다.
 *
 * <p>세 가지를 서로 대체하지 않는다: 연결의 생성 출처(topic_material_links.origin=USER), 파일 업로드 여부,
 * <b>이번 요청의 자료 지정</b>. 여기서 다루는 것은 마지막 하나다.
 * <ul>
 *   <li>화면이 id를 알면 그대로 받는다(자료함의 [이 자료로 계획], 되묻기 선택). 소유·활성·범위를 검증하고, 하나라도
 *       맞지 않으면 400이다 — 조용히 떨구면 사용자는 지정했다고 믿는데 계획은 그 자료를 모른다.</li>
 *   <li>자유 문장(계획 지시·상담 대화)에 파일 이름이 들어 있으면 <b>접근 가능한 목록 안에서만</b> 찾는다. 대상 프로젝트에
 *       연결된 자료가 목록이다. 다른 프로젝트 자료를 전역 검색해 끌어오지 않는다.</li>
 *   <li>같은 이름이 둘 이상이면 고르지 않고 {@link Ambiguity}로 돌려준다 — 화면이 필요할 때만 묻는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PlanRequestedMaterialResolver {

    /** 이름이 너무 짧으면 문장 속 우연한 일치가 많다("1주차" 같은). */
    static final int MIN_MATCH_NAME_LENGTH = 4;

    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialLinkMapper materialLinkMapper;
    private final MaterialSectionMapper sectionMapper;

    public enum Source { EXPLICIT, INSTRUCTION }

    public record RequestedMaterial(Long materialId, String filename, Long courseId, Source source) {
    }

    public record Candidate(Long materialId, String filename, Long courseId) {
    }

    /** 문장이 가리키는 자료가 여럿이라 고르지 않았다. */
    public record Ambiguity(String mention, List<Candidate> candidates) {
    }

    /**
     * @param materials     확정된 지정 자료(순서 유지)
     * @param sectionIds    확정된 지정 구간
     * @param unscoped      대상 프로젝트 어디에도 연결되지 않은 지정 자료 id — 프로젝트 없는 묶음으로 따로 후보가 된다
     */
    public record Resolution(List<RequestedMaterial> materials, List<Long> sectionIds, List<Ambiguity> ambiguities,
                             Set<Long> unscoped) {

        public static Resolution empty() {
            return new Resolution(List.of(), List.of(), List.of(), Set.of());
        }

        public Set<Long> materialIds() {
            return materials.stream().map(RequestedMaterial::materialId).collect(Collectors.toCollection(LinkedHashSet::new));
        }

        public Set<Long> materialIdsOfCourse(Long courseId) {
            return materials.stream().filter(m -> Objects.equals(m.courseId(), courseId))
                    .map(RequestedMaterial::materialId).collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    /**
     * @param courses              이번 계획의 대상 프로젝트(이미 소유 검증됨)
     * @param explicitScope        courseIds를 사용자가 좁혔는가. 좁혔으면 범위 밖 자료 지정은 거절한다
     */
    public Resolution resolve(Long userId, List<Course> courses, boolean explicitScope,
                              Collection<Long> materialIds, Collection<Long> sectionIds, String instruction) {
        Set<Long> courseIds = courses.stream().map(Course::getCourseId).collect(Collectors.toSet());
        Map<Long, RequestedMaterial> resolved = new LinkedHashMap<>();
        Set<Long> unscoped = new LinkedHashSet<>();

        for (Long materialId : materialIds == null ? List.<Long>of() : materialIds) {
            if (materialId == null || resolved.containsKey(materialId)) {
                continue;
            }
            CourseMaterial material = courseMaterialMapper.findByIdAndUserId(materialId, userId);
            if (material == null) {
                throw new BadRequestException(ErrorCode.PLAN_REQUESTED_MATERIAL_INVALID);
            }
            Long courseId = courseOf(userId, materialId, courseIds);
            if (courseId == null) {
                if (explicitScope) {
                    throw new BadRequestException(ErrorCode.PLAN_REQUESTED_MATERIAL_INVALID);
                }
                unscoped.add(materialId);
            }
            resolved.put(materialId, new RequestedMaterial(materialId, material.getOriginalFilename(), courseId,
                    Source.EXPLICIT));
        }

        List<Long> sections = new ArrayList<>();
        for (Long sectionId : sectionIds == null ? List.<Long>of() : sectionIds) {
            if (sectionId == null || sections.contains(sectionId)) {
                continue;
            }
            MaterialSection section = sectionMapper.findByIdAndUserId(sectionId, userId);
            if (section == null || !"ACTIVE".equals(section.getStatus())) {
                throw new BadRequestException(ErrorCode.PLAN_REQUESTED_MATERIAL_INVALID);
            }
            CourseMaterial material = courseMaterialMapper.findByIdAndUserId(section.getMaterialId(), userId);
            if (material == null || !Objects.equals(material.getFileHash(), section.getFileHash())) {
                throw new BadRequestException(ErrorCode.PLAN_REQUESTED_MATERIAL_INVALID);
            }
            if (!resolved.containsKey(material.getMaterialId())) {
                Long courseId = courseOf(userId, material.getMaterialId(), courseIds);
                if (courseId == null) {
                    if (explicitScope) {
                        throw new BadRequestException(ErrorCode.PLAN_REQUESTED_MATERIAL_INVALID);
                    }
                    unscoped.add(material.getMaterialId());
                }
            }
            sections.add(sectionId);
        }

        List<Ambiguity> ambiguities = new ArrayList<>();
        if (instruction != null && !instruction.isBlank()) {
            String haystack = normalize(instruction);
            Map<String, List<Candidate>> byName = new LinkedHashMap<>();
            for (Course course : courses) {
                for (CourseMaterial material : courseMaterialMapper.findByCourseIdAndUserId(course.getCourseId(), userId)) {
                    String name = normalize(stem(material.getOriginalFilename()));
                    if (name.length() < MIN_MATCH_NAME_LENGTH) {
                        continue;
                    }
                    byName.computeIfAbsent(name, k -> new ArrayList<>())
                            .add(new Candidate(material.getMaterialId(), material.getOriginalFilename(), course.getCourseId()));
                }
            }
            for (Map.Entry<String, List<Candidate>> entry : byName.entrySet()) {
                if (!haystack.contains(entry.getKey())) {
                    continue;
                }
                List<Candidate> distinct = entry.getValue().stream()
                        .collect(Collectors.toMap(Candidate::materialId, c -> c, (a, b) -> a, LinkedHashMap::new))
                        .values().stream().toList();
                if (distinct.size() == 1) {
                    Candidate only = distinct.get(0);
                    resolved.putIfAbsent(only.materialId(), new RequestedMaterial(only.materialId(), only.filename(),
                            only.courseId(), Source.INSTRUCTION));
                } else if (distinct.stream().noneMatch(c -> resolved.containsKey(c.materialId()))) {
                    ambiguities.add(new Ambiguity(distinct.get(0).filename(), distinct));
                }
            }
        }
        return new Resolution(new ArrayList<>(resolved.values()), sections, ambiguities, unscoped);
    }

    /** 대상 프로젝트 중 이 자료가 연결된 첫 프로젝트. 없으면 null. */
    private Long courseOf(Long userId, Long materialId, Set<Long> courseIds) {
        for (MaterialLink link : materialLinkMapper.findByMaterialIdAndUserId(materialId, userId)) {
            if (courseIds.contains(link.getCourseId())) {
                return link.getCourseId();
            }
        }
        return null;
    }

    static String stem(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /** 공백·밑줄·하이픈을 없애고 소문자로. "자료구조_3주차.pdf"와 "자료구조 3주차 pdf"를 같게 본다. */
    static String normalize(String text) {
        String n = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return n.replaceAll("[\\s_\\-·.()\\[\\]]+", "");
    }
}
