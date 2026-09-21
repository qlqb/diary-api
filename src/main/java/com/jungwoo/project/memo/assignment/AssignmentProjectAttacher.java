package com.jungwoo.project.memo.assignment;

import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 자료에서 나온 과제 후보에 프로젝트를 붙인다. <b>AI를 부르지 않는다.</b>
 *
 * <p>예전에는 자료별 LINK 분석이 이 일을 곁들여 했다. 그 분석을 없애면서(프로젝트 단위 정리로
 * 옮기면서) 과제 후보가 프로젝트를 영영 못 받게 되는 구멍이 생겼다 — 과제는 정리안과 무관하게
 * "이 자료가 이 프로젝트에 붙어 있다"만으로 정해지는 사실이라, 모델 없이 여기서 잇는다.
 *
 * <p>자료가 여러 프로젝트에 붙어 있으면 붙이지 않는다. 어느 쪽 과제인지 서버가 정할 근거가
 * 없고, 잘못 붙이면 사용자가 다른 프로젝트에서 모르는 과제를 보게 된다.
 *
 * <p>이미 프로젝트가 있는 과제는 건드리지 않는다({@code attachCourseIfMissing}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentProjectAttacher {

    private final CourseAssignmentMapper assignmentMapper;
    private final MaterialLinkMapper materialLinkMapper;

    /** 한 틱에 처리할 자료 수. 폴러가 조금씩 소화한다. */
    static final int BATCH = 20;

    /**
     * 프로젝트가 비어 있는 과제 후보를 훑어 잇는다.
     *
     * @return 붙인 과제 수
     */
    @Transactional
    public int attachPending() {
        List<CourseAssignment> pending = assignmentMapper.findUnattachedCandidates(BATCH);
        Map<String, List<MaterialLink>> linksCache = new HashMap<>();
        int attached = 0;
        for (CourseAssignment assignment : pending) {
            String key = assignment.getUserId() + ":" + assignment.getMaterialId();
            List<MaterialLink> links = linksCache.computeIfAbsent(key, k ->
                    materialLinkMapper.findByMaterialIdAndUserId(assignment.getMaterialId(),
                            assignment.getUserId()));
            if (links.size() != 1) {
                // 연결이 없거나 여럿이다. 서버가 어느 프로젝트인지 정할 근거가 없다.
                continue;
            }
            assignmentMapper.attachCourseIfMissing(assignment.getAssignmentId(), links.get(0).getCourseId(), null);
            attached++;
        }
        if (attached > 0) {
            log.info("과제 후보에 프로젝트 연결: {}건", attached);
        }
        return attached;
    }
}
