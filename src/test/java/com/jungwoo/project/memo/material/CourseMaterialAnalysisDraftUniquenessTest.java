package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.CourseMaterialAnalysis;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 열린 DRAFT는 맥락당 하나라는 불변조건을 실제 DB에 대고 검증한다.
 *
 * <p>서비스의 사전 조회는 경쟁을 못 막는다 — 두 요청이 동시에 "없음"을 읽을 수 있다. 그래서
 * 최종 방어선은 uq_course_material_analyses_single_draft이고, 그 인덱스가 실제로 무는지는
 * Mockito로 증명할 수 없다.
 *
 * <p><b>이 테스트는 docs/sql/2026-08-31-material-analysis-single-draft.sql을 적용한 뒤에만
 * 통과한다.</b> 적용 전에는 1번이 실패한다(중복이 그대로 들어간다) — 그게 이 마이그레이션이
 * 필요한 이유이기도 하다. CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class CourseMaterialAnalysisDraftUniquenessTest {

    private static final Long TEST_USER_ID = 999_000_021L;
    private static final Long COURSE_ID = 999_100_001L;
    private static final Long OTHER_COURSE_ID = 999_100_002L;
    private static final Long MATERIAL_ID = 999_200_001L;
    private static final Long OTHER_MATERIAL_ID = 999_200_002L;

    @Autowired
    private CourseMaterialAnalysisMapper analysisMapper;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM course_material_analyses WHERE user_id = ?")) {
            ps.setLong(1, TEST_USER_ID);
            ps.executeUpdate();
        }
    }

    @Test
    void 같은_맥락에_DRAFT를_두_번_넣으면_DB가_막는다() {
        analysisMapper.insert(draft(COURSE_ID, MATERIAL_ID));

        assertThatThrownBy(() -> analysisMapper.insert(draft(COURSE_ID, MATERIAL_ID)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /** 검토를 끝내면(폐기·적용) 그 자리가 열려야 한다. 아니면 다시 분석할 방법이 없어진다. */
    @Test
    void 폐기한_뒤에는_새_DRAFT를_넣을_수_있다() {
        CourseMaterialAnalysis first = draft(COURSE_ID, MATERIAL_ID);
        analysisMapper.insert(first);
        analysisMapper.updateStatus(first.getAnalysisId(), MaterialAnalysisStatus.DISMISSED.name(), null);

        assertThatCode(() -> analysisMapper.insert(draft(COURSE_ID, MATERIAL_ID)))
                .doesNotThrowAnyException();
    }

    @Test
    void 적용한_뒤에도_새_DRAFT를_넣을_수_있다() {
        CourseMaterialAnalysis first = draft(COURSE_ID, MATERIAL_ID);
        analysisMapper.insert(first);
        analysisMapper.updateStatus(first.getAnalysisId(), MaterialAnalysisStatus.APPLIED.name(), null);

        assertThatCode(() -> analysisMapper.insert(draft(COURSE_ID, MATERIAL_ID)))
                .doesNotThrowAnyException();
    }

    /**
     * 이력은 여러 건 남아야 한다. UNIQUE(user, course, material, status)를 쓰지 않고 생성
     * 컬럼을 둔 이유가 이것이다 — DRAFT만 하나고 나머지 상태는 제한이 없다.
     */
    @Test
    void APPLIED_DISMISSED_FAILED_이력은_여러_건_남는다() {
        assertThatCode(() -> {
            analysisMapper.insert(withStatus(MaterialAnalysisStatus.APPLIED));
            analysisMapper.insert(withStatus(MaterialAnalysisStatus.APPLIED));
            analysisMapper.insert(withStatus(MaterialAnalysisStatus.DISMISSED));
            analysisMapper.insert(withStatus(MaterialAnalysisStatus.FAILED));
        }).doesNotThrowAnyException();

        assertThat(analysisMapper.findByMaterialIdAndCourseIdAndUserId(MATERIAL_ID, COURSE_ID, TEST_USER_ID))
                .hasSize(4);
    }

    /** 맥락이 다르면 각각 하나씩 가질 수 있다. 유일성은 맥락 안에서만 성립한다. */
    @Test
    void 프로젝트나_자료가_다르면_각각_DRAFT를_가진다() {
        assertThatCode(() -> {
            analysisMapper.insert(draft(COURSE_ID, MATERIAL_ID));
            analysisMapper.insert(draft(OTHER_COURSE_ID, MATERIAL_ID));
            analysisMapper.insert(draft(COURSE_ID, OTHER_MATERIAL_ID));
        }).doesNotThrowAnyException();
    }

    /** 사전 조회가 맥락 안의 DRAFT만, 그것도 하나만 돌려주는지. */
    @Test
    void findLatestDraftByContext는_그_맥락의_DRAFT만_돌려준다() {
        analysisMapper.insert(withStatus(MaterialAnalysisStatus.APPLIED));
        analysisMapper.insert(draft(OTHER_COURSE_ID, MATERIAL_ID));
        CourseMaterialAnalysis mine = draft(COURSE_ID, MATERIAL_ID);
        analysisMapper.insert(mine);

        CourseMaterialAnalysis found =
                analysisMapper.findLatestDraftByContext(TEST_USER_ID, COURSE_ID, MATERIAL_ID);

        assertThat(found).isNotNull();
        assertThat(found.getAnalysisId()).isEqualTo(mine.getAnalysisId());
        assertThat(analysisMapper.findLatestDraftByContext(TEST_USER_ID, COURSE_ID, OTHER_MATERIAL_ID)).isNull();
    }

    private CourseMaterialAnalysis draft(Long courseId, Long materialId) {
        return CourseMaterialAnalysis.builder()
                .userId(TEST_USER_ID)
                .courseId(courseId)
                .materialId(materialId)
                .status(MaterialAnalysisStatus.DRAFT)
                .analysisJson("{}")
                .build();
    }

    private CourseMaterialAnalysis withStatus(MaterialAnalysisStatus status) {
        CourseMaterialAnalysis analysis = draft(COURSE_ID, MATERIAL_ID);
        analysis.setStatus(status);
        return analysis;
    }
}
