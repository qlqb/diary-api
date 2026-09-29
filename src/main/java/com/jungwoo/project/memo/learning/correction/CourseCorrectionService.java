package com.jungwoo.project.memo.learning.correction;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.week.MaterialWeekService;
import com.jungwoo.project.memo.learning.week.dto.MaterialWeekRequests;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 실제 수업 진행·계획 범위 정정. 교재상의 위치(트리)·실제 수업 순서·이번 계획 범위를 서로 다른 데이터로 둔다.
 *
 * <ul>
 *   <li>CLASS: 실제로 다룬 주차·순서. 트리의 부모·순서는 그대로다 — "5장을 3장보다 먼저 수업했어"는 항목을 복제하거나
 *       교재 구조를 바꾸지 않는다.</li>
 *   <li>MATERIAL_WEEK: 자료의 실제 주차. 자료 주차 확인과 같은 저장(사용자 확정)을 쓴다 — 주차 확정은 한 곳이다.</li>
 *   <li>SCOPE_EXCLUDE: 시험·계획 범위 제외. 학습 완료로 보지 않고, 트리에서 지우지 않는다. 계획 만들기의 기본 제외다.</li>
 * </ul>
 *
 * <p>쓰는 길은 사용자가 적용한 정리안뿐이다({@link #apply}). 재분석·자동 분석은 이 표들을 모른다 — 다음 분석과
 * 다음 계획에서도 정정이 그대로 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseCorrectionService {

    private final CourseCorrectionMapper mapper;
    private final CourseTopicMapper topicMapper;
    private final CourseMapper courseMapper;
    private final MaterialWeekService materialWeekService;

    public record Applied(int classChanges, int materialWeeks, int scopeExclusions) {
    }

    /** 정리안 적용 트랜잭션 안에서 부른다. 트리 작업은 이미 끝난 뒤다. */
    @Transactional
    public Applied apply(Long userId, Long courseId, List<TopicChangeOp> ops) {
        int klass = 0, weeks = 0, scopes = 0;
        for (TopicChangeOp op : ops) {
            switch (op.op()) {
                case TopicChangeOp.CLASS -> {
                    applyClass(userId, courseId, op);
                    klass++;
                }
                case TopicChangeOp.MATERIAL_WEEK -> {
                    materialWeekService.place(userId, courseId, op.materialId(),
                            new MaterialWeekRequests.Place("WEEK", List.of(op.week()), "USER"));
                    weeks++;
                }
                case TopicChangeOp.SCOPE_EXCLUDE -> {
                    mapper.upsertExclusion(CourseScopeExclusion.builder().userId(userId).courseId(courseId)
                            .topicId(op.topicId()).label(op.label() == null ? "" : op.label()).build());
                    scopes++;
                }
                default -> {
                }
            }
        }
        if (klass + weeks + scopes > 0) {
            log.info("실제 수업·범위 정정 적용: courseId={}, class={}, materialWeek={}, scope={}",
                    courseId, klass, weeks, scopes);
        }
        return new Applied(klass, weeks, scopes);
    }

    /**
     * 주차는 그 칸만 바꾼다. 순서(afterTopicId)가 있으면 실제 수업 순서 목록에서 그 항목을 기준 항목 뒤(0이면 맨 앞)로
     * 옮기고 1부터 다시 번호를 매긴다. 기준 항목이 목록에 없으면 먼저 목록 끝에 둔다(순서를 아는 항목만 목록에 있다).
     */
    private void applyClass(Long userId, Long courseId, TopicChangeOp op) {
        mapper.upsertClassProgress(TopicClassProgress.builder().userId(userId).courseId(courseId)
                .topicId(op.topicId()).weekNo(op.week()).build());
        Long after = op.afterTopicId();
        if (after == null) {
            return;
        }
        List<Long> order = new ArrayList<>();
        for (TopicClassProgress row : mapper.findClassProgress(courseId, userId)) {
            if (row.getClassSeq() != null) {
                order.add(row.getTopicId());
            }
        }
        order.remove(op.topicId());
        if (after == 0L) {
            order.add(0, op.topicId());
        } else {
            if (!order.contains(after)) {
                mapper.upsertClassProgress(TopicClassProgress.builder().userId(userId).courseId(courseId)
                        .topicId(after).build());
                order.add(after);
            }
            order.add(order.indexOf(after) + 1, op.topicId());
        }
        for (int i = 0; i < order.size(); i++) {
            mapper.updateClassSeq(courseId, userId, order.get(i), i + 1);
        }
    }

    // ===== 읽기 =====

    public record ClassLine(Long topicId, String title, Integer classSeq, Integer weekNo) {
    }

    public record ExclusionLine(Long exclusionId, Long topicId, String title, String label) {
    }

    public record CorrectionsView(Long courseId, List<ClassLine> classProgress, List<ExclusionLine> exclusions) {
    }

    @Transactional(readOnly = true)
    public CorrectionsView view(Long userId, Long courseId) {
        if (courseMapper.findByIdAndUserId(courseId, userId) == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        Map<Long, String> titles = new LinkedHashMap<>();
        for (CourseTopic topic : topicMapper.findActiveByCourseIdAndUserId(courseId, userId)) {
            titles.put(topic.getTopicId(), topic.getTitle());
        }
        List<ClassLine> lines = new ArrayList<>();
        for (TopicClassProgress row : mapper.findClassProgress(courseId, userId)) {
            lines.add(new ClassLine(row.getTopicId(), titles.get(row.getTopicId()), row.getClassSeq(), row.getWeekNo()));
        }
        List<ExclusionLine> exclusions = new ArrayList<>();
        for (CourseScopeExclusion row : mapper.findActiveExclusions(courseId, userId)) {
            exclusions.add(new ExclusionLine(row.getExclusionId(), row.getTopicId(), titles.get(row.getTopicId()),
                    row.getLabel() == null || row.getLabel().isBlank() ? null : row.getLabel()));
        }
        return new CorrectionsView(courseId, lines, exclusions);
    }

    /** 범위 제외를 푼다(사용자 조작). 기록·트리는 그대로다. */
    @Transactional
    public CorrectionsView removeExclusion(Long userId, Long courseId, Long exclusionId) {
        if (mapper.removeExclusion(exclusionId, courseId, userId) == 0) {
            throw new NotFoundException(ErrorCode.ENTITY_NOT_FOUND);
        }
        return view(userId, courseId);
    }

    /** 계획 만들기가 기본으로 빼는 학습 항목(그 하위 포함은 호출자가 정한다). */
    @Transactional(readOnly = true)
    public Map<Long, List<CourseScopeExclusion>> exclusionsByCourse(Long userId, List<Long> courseIds) {
        Map<Long, List<CourseScopeExclusion>> out = new LinkedHashMap<>();
        if (courseIds == null || courseIds.isEmpty()) {
            return out;
        }
        for (CourseScopeExclusion row : mapper.findActiveExclusionsForCourses(
                courseIds.stream().filter(Objects::nonNull).distinct().toList(), userId)) {
            out.computeIfAbsent(row.getCourseId(), k -> new ArrayList<>()).add(row);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<TopicClassProgress> classProgress(Long userId, Long courseId) {
        return mapper.findClassProgress(courseId, userId);
    }
}
