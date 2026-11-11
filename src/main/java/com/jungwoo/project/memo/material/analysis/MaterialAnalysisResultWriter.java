package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.material.MaterialAnalysisJobMapper;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 결과(구간·과제 후보·변경안)를 임대가 유효할 때만 쓴다.
 *
 * <p>모델 응답을 기다리는 동안 자료가 지워지거나 임대가 다른 worker로 넘어갈 수 있다. 응답이 돌아온 뒤
 * "확인하고 쓰기"를 두 문장으로 하면 그 사이에 또 바뀔 수 있으므로, 한 트랜잭션 안에서 작업 행을
 * {@code FOR UPDATE}로 잡은 채 토큰을 대조하고 그대로 쓴다. 취소·재선점은 같은 행의 UPDATE라 이 트랜잭션이
 * 끝날 때까지 기다리고, 끝난 뒤에는 토큰이 달라져 늦은 쪽의 다음 쓰기가 0건이 된다.
 *
 * <p>분석기 자신은 트랜잭션이 아니다(모델 호출이 길다). 쓰기 구간만 이 빈을 거친다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialAnalysisResultWriter {

    private final MaterialAnalysisJobMapper jobMapper;

    /**
     * @return true면 work가 실행되고 커밋됐다. false면 임대를 잃어 아무것도 쓰지 않았다 — 호출자는 물러난다.
     */
    @Transactional
    public boolean writeIfLeased(MaterialAnalysisJob job, Runnable work) {
        Long locked = jobMapper.lockIfLeased(job.getJobId(), job.getLeaseToken());
        if (locked == null) {
            log.info("임대가 바뀌어 분석 결과를 버림: jobId={}, kind={}, materialId={}",
                    job.getJobId(), job.getJobKind(), job.getMaterialId());
            return false;
        }
        work.run();
        return true;
    }
}
