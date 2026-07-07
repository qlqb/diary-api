package com.jungwoo.project.memo.dailyplan;

import com.jungwoo.project.memo.dailyplan.domain.DailyPlan;
import com.jungwoo.project.memo.dailyplan.domain.DailyPlanIntensity;
import com.jungwoo.project.memo.dailyplan.domain.DailyPlanViewMode;
import com.jungwoo.project.memo.dailyplan.domain.ViewModeSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyPlanService {

    private final DailyPlanMapper dailyPlanMapper;

    /**
     * 시스템 기본값.
     * 1차-C에서 user_plan_preferences 조회로 교체한다. (교체 지점은 이 두 상수뿐)
     */
    private static final DailyPlanViewMode DEFAULT_VIEW_MODE = DailyPlanViewMode.CHECKLIST;
    private static final DailyPlanIntensity DEFAULT_INTENSITY = DailyPlanIntensity.NORMAL;

    /**
     * 대상 날짜의 DailyPlan을 조회하고, 없으면 기본값으로 생성한다.
     *
     * move 액션의 필수 단계: 이동 시점에 내일의 DailyPlan은 아직 존재하지 않을 수 있다.
     * (DailyPlan은 원래 앱 진입 시 생성되므로)
     *
     * 반드시 호출자의 트랜잭션 안에서 실행되어야 한다.
     * 동시 생성 경합은 UNIQUE(user_id, plan_date) + 재조회로 해소한다.
     */
    @Transactional
    public DailyPlan getOrCreate(Long userId, LocalDate planDate) {
        DailyPlan existing = dailyPlanMapper.findByUserIdAndDate(userId, planDate);
        if (existing != null) {
            return existing;
        }

        DailyPlan created = DailyPlan.builder()
                .userId(userId)
                .planDate(planDate)
                .viewMode(DEFAULT_VIEW_MODE)
                .viewModeSource(ViewModeSource.USER_DEFAULT)
                .intensity(DEFAULT_INTENSITY)
                .build();

        try {
            dailyPlanMapper.insert(created);
            log.info("DailyPlan 기본값 생성: userId={}, planDate={}, dailyPlanId={}",
                    userId, planDate, created.getDailyPlanId());
            return created;
        } catch (DuplicateKeyException e) {
            // 동시 요청이 먼저 생성한 경우: 재조회로 해소
            log.info("DailyPlan 동시 생성 감지, 재조회: userId={}, planDate={}", userId, planDate);
            return dailyPlanMapper.findByUserIdAndDate(userId, planDate);
        }
    }
}
