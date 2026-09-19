package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.MaterialAnalysisControlMapper;
import com.jungwoo.project.memo.material.MaterialAnalysisJobMapper;
import com.jungwoo.project.memo.material.domain.AnalysisJobKind;
import com.jungwoo.project.memo.material.domain.AnalysisJobStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * handoff T16 — 내용 분석 한도에 닿아도 실행 가능한 종류(구조 연결)와 다른 사용자의 작업은 계속 돈다.
 *
 * <p>재현하는 실패(2026-09-19 진단): 하루 한도 60이 CONTENT+LINK를 합쳐 세서, 자료 36개를 올리고 프로젝트를 다시 연결한
 * 계정의 LINK 작업이 다음 날까지 멈췄다. 게다가 우선순위 앞쪽 후보가 전부 한도에 걸리면 뒤의 실행 가능한 작업도 조회되지 않았다.
 */
class MaterialAnalysisDailyLimitTest {

    private final MaterialAnalysisJobMapper jobMapper = mock(MaterialAnalysisJobMapper.class);
    private final MaterialAnalysisJobService service = new MaterialAnalysisJobService(jobMapper,
            mock(CourseMaterialMapper.class), mock(MaterialAnalysisControlMapper.class), mock(FileStorageService.class));

    private static MaterialAnalysisJob job(long id, long userId, AnalysisJobKind kind) {
        return MaterialAnalysisJob.builder().jobId(id).userId(userId).materialId(id).jobKind(kind).priority(5)
                .status(AnalysisJobStatus.QUEUED).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 내용_분석_한도에_닿으면_그_종류만_기다리고_구조_연결과_다른_사용자는_계속_처리된다() {
        ReflectionTestUtils.setField(service, "dailyJobLimit", 2);
        ReflectionTestUtils.setField(service, "dailyLinkJobLimit", 5);
        List<MaterialAnalysisJob> contentHead = List.of(job(1, 7, AnalysisJobKind.CONTENT), job(2, 7, AnalysisJobKind.CONTENT),
                job(3, 7, AnalysisJobKind.CONTENT));
        List<MaterialAnalysisJob> behind = List.of(job(10, 7, AnalysisJobKind.LINK), job(11, 8, AnalysisJobKind.CONTENT));
        List<List<Map<String, Object>>> blockedSeen = new ArrayList<>();
        when(jobMapper.findClaimable(any(), isNull(), anyInt(), anyList())).thenAnswer(inv -> {
            List<Map<String, Object>> blocked = inv.getArgument(3);
            blockedSeen.add(List.copyOf(blocked));
            // 첫 조회는 우선순위 앞쪽(전부 사용자 7의 CONTENT)만 돌려준다 — 한도 때문에 하나도 못 잡는 상황.
            return blocked.isEmpty() ? contentHead : behind;
        });
        when(jobMapper.findClaimable(any(), eq(MaterialAnalysisJobService.PRIORITY_BACKFILL), anyInt(), anyList()))
                .thenReturn(List.of());
        when(jobMapper.countStartedSinceByKind(eq(7L), any(), eq("CONTENT"))).thenReturn(2); // 한도 2에 닿음
        when(jobMapper.countStartedSinceByKind(eq(7L), any(), eq("LINK"))).thenReturn(0);
        when(jobMapper.countStartedSinceByKind(eq(8L), any(), eq("CONTENT"))).thenReturn(0);
        when(jobMapper.claim(anyLong(), any(), any(), any())).thenReturn(1);
        when(jobMapper.findById(anyLong())).thenAnswer(inv -> behind.stream()
                .filter(j -> j.getJobId().equals(inv.getArgument(0))).findFirst().orElseThrow());

        List<MaterialAnalysisJob> claimed = service.claimNext("worker-1", 2);

        assertThat(claimed).extracting(MaterialAnalysisJob::getJobId).containsExactly(10L, 11L);
        // 한도에 걸린 CONTENT는 선점하지 않는다(QUEUED로 남아 날짜가 바뀌면 다시 잡힌다).
        verify(jobMapper, never()).claim(eq(1L), any(), any(), any());
        // 두 번째 조회는 (사용자 7, CONTENT)를 빼고 찾았다.
        assertThat(blockedSeen.get(1)).singleElement()
                .satisfies(b -> assertThat(b).containsEntry("userId", 7L).containsEntry("kind", "CONTENT"));
    }

    @Test
    void 한도_상태는_종류별_사용량과_다시_처리되는_시각을_말한다() {
        ReflectionTestUtils.setField(service, "dailyJobLimit", 60);
        ReflectionTestUtils.setField(service, "dailyLinkJobLimit", 120);
        when(jobMapper.countStartedSinceByKind(eq(7L), any(), eq("CONTENT"))).thenReturn(60);
        when(jobMapper.countStartedSinceByKind(eq(7L), any(), eq("LINK"))).thenReturn(12);

        MaterialAnalysisJobService.DailyLimitStatus status = service.dailyLimitStatus(7L);

        assertThat(status.contentReached()).isTrue();
        assertThat(status.linkReached()).isFalse();
        assertThat(status.reached()).isTrue();
        assertThat(status.resumesAt()).isEqualTo(java.time.LocalDate.now().plusDays(1).atStartOfDay());

        when(jobMapper.countStartedSinceByKind(eq(7L), any(), eq("CONTENT"))).thenReturn(3);
        assertThat(service.dailyLimitStatus(7L).resumesAt()).isNull();
    }

    @Test
    void 한도에_걸린_후보뿐이면_유한한_횟수만_다시_찾고_멈춘다() {
        ReflectionTestUtils.setField(service, "dailyJobLimit", 0);
        ReflectionTestUtils.setField(service, "dailyLinkJobLimit", 0);
        long[] next = {100};
        when(jobMapper.findClaimable(any(), any(), anyInt(), anyList())).thenAnswer(inv ->
                // 조회할 때마다 새 사용자의 작업이 나온다 — 그래도 끝없이 돌지 않는다.
                List.of(job(next[0], next[0]++, AnalysisJobKind.CONTENT)));
        when(jobMapper.countStartedSinceByKind(anyLong(), any(), any())).thenReturn(0);

        assertThat(service.claimNext("worker-1", 2)).isEmpty();
        verify(jobMapper, never()).claim(anyLong(), any(), any(), any());
        verify(jobMapper, org.mockito.Mockito.atMost(MaterialAnalysisJobService.MAX_CLAIM_ROUNDS * 2))
                .findClaimable(any(), any(), anyInt(), anyList());
    }
}
