package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정리안을 임대가 유효할 때만 쓴다. "버린 안이 되살아나지 않는다"를 지키는 자리다.
 *
 * <p>모델이 답하는 동안 사용자가 [버리기]를 누르면 그 트랜잭션이 작업 행의 lease_token을 올린다.
 * 여기서는 작업 행을 {@code FOR UPDATE}로 잡은 채 토큰을 대조하고 <b>같은 트랜잭션에서</b>
 * 정리안을 쓴다 — 확인과 쓰기 사이에 끼어들 틈이 없다. 취소가 먼저면 토큰이 달라져 아무것도
 * 쓰지 않고, 쓰기가 먼저면 취소가 기다렸다가 그 정리안을 폐기한다. 어느 순서든 "버렸는데 살아
 * 있는" 상태는 만들어지지 않는다.
 *
 * <p>모델 호출은 이 트랜잭션 밖에 있다. 길게 잡고 있으면 그동안 같은 프로젝트의 조회가 막힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectTidyResultWriter {

    private final ProjectTidyMapper tidyMapper;

    /**
     * @return true면 work가 실행되고 커밋됐다. false면 요청이 취소·교체되어 아무것도 쓰지 않았다
     */
    @Transactional
    public boolean writeIfLeased(ProjectTidyJob job, Runnable work) {
        Long locked = tidyMapper.lockJobIfLeased(job.getJobId(), job.getLeaseToken());
        if (locked == null) {
            log.info("임대가 바뀌어 정리안을 버림: jobId={}, courseId={}", job.getJobId(), job.getCourseId());
            return false;
        }
        work.run();
        return true;
    }
}
