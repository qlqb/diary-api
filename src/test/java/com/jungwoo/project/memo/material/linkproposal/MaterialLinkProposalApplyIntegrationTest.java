package com.jungwoo.project.memo.material.linkproposal;

import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.domain.ProposalAction;
import com.jungwoo.project.memo.material.dto.LinkProposalApplyRequest;
import com.jungwoo.project.memo.material.dto.LinkProposalApplyResponse;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * apply의 원자성과 동시성을 실제 로컬 MariaDB(memo)에 대고 검증한다.
 *
 * ★ 테스트 메서드에 @Transactional을 붙이지 않는다. 붙이면 테스트 트랜잭션이 끝에서 전부
 *   롤백해버려, 서비스에 @Transactional이 없어도 통과한다 — 검증하려는 결함을 테스트
 *   프레임워크가 가리게 된다.
 *
 * 전제: course_materials / courses / material_links가 InnoDB여야 한다. 하나라도 MyISAM이면
 * 트랜잭션도 행 잠금도 성립하지 않아 이 테스트가 증명하는 것이 없어진다.
 *
 * 스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class MaterialLinkProposalApplyIntegrationTest {

    private static final String TITLE_PREFIX = "MLP-";
    private static final String FILENAME_PREFIX = "MLP-";

    @Autowired
    private MaterialLinkProposalService service;
    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private MaterialLinkMapper materialLinkMapper;

    /**
     * 스파이가 가로챈 INSERT를 실제로 흘려보내는 통로.
     *
     * invocation.callRealMethod()를 쓸 수 없다 — MyBatis 매퍼는 인터페이스라 "실제 메서드"가
     * 없다("Cannot call abstract real method"). SqlSession에서 새 매퍼 프록시를 받아 부르면
     * 현재 Spring 트랜잭션에 그대로 참여하므로 롤백 검증이 성립한다.
     */
    @Autowired
    private SqlSession sqlSession;

    private Long userId;
    private final List<Long> createdMaterialIds = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        Mockito.reset(materialLinkMapper);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM material_links WHERE course_id IN "
                    + "(SELECT course_id FROM courses WHERE title LIKE '" + TITLE_PREFIX + "%')");
            for (Long materialId : createdMaterialIds) {
                exec(conn, "DELETE FROM material_links WHERE material_id = " + materialId);
                exec(conn, "DELETE FROM course_materials WHERE material_id = " + materialId);
            }
            exec(conn, "DELETE FROM courses WHERE title LIKE '" + TITLE_PREFIX + "%'");
        }
        createdMaterialIds.clear();
    }

    // ===== (a) 사전 검증 실패 시 쓰기 없음 =====

    /**
     * 이건 롤백 테스트가 아니다. 검증을 전부 마친 뒤에 쓰기를 시작하므로 첫 INSERT 자체가
     * 실행되지 않는다 — 서비스의 @Transactional을 지워도 통과한다. "검증과 쓰기가 분리되어
     * 있다"를 지키는 테스트로만 쓴다. 진짜 롤백은 아래 (b)가 본다.
     */
    @Test
    @DisplayName("(a) 두 번째 묶음의 목적지가 무효하면 첫 번째 프로젝트도 만들어지지 않는다")
    void doesNotWriteAnythingWhenAnyGroupFailsVerification() {
        Long first = givenUnlinkedMaterial("운영체제 강의계획서");
        Long second = givenUnlinkedMaterial("자료구조 강의계획서");

        LinkProposalApplyRequest request = request(
                createGroup(TITLE_PREFIX + "운영체제", first),
                linkGroup(999_999_999L, second));

        assertThatThrownBy(() -> service.apply(userId(), request)).isInstanceOf(RuntimeException.class);

        assertThat(countRows("courses", "title = '" + TITLE_PREFIX + "운영체제'")).isZero();
        assertThat(countRows("material_links", "material_id = " + first)).isZero();
    }

    // ===== (b) 쓰기 도중 실패 시 롤백 =====

    @Test
    @DisplayName("(b) 두 번째 묶음의 링크 INSERT가 터지면 첫 번째 묶음의 프로젝트와 링크도 남지 않는다")
    void rollsBackEverythingWhenAWriteFailsMidway() {
        Long first = givenUnlinkedMaterial("운영체제 강의계획서");
        Long second = givenUnlinkedMaterial("자료구조 강의계획서");

        // 첫 번째 그룹의 INSERT는 실제로 실행되어야 한다 — 그래야 "이미 쓴 것을 되돌린다"를 본다.
        AtomicInteger inserts = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (inserts.incrementAndGet() == 2) {
                throw new IllegalStateException("두 번째 링크에서 터진다");
            }
            sqlSession.getMapper(MaterialLinkMapper.class).insert(invocation.getArgument(0));
            return null;
        }).when(materialLinkMapper).insert(Mockito.any());

        LinkProposalApplyRequest request = request(
                createGroup(TITLE_PREFIX + "운영체제", first),
                createGroup(TITLE_PREFIX + "자료구조", second));

        assertThatThrownBy(() -> service.apply(userId(), request)).isInstanceOf(IllegalStateException.class);

        assertThat(inserts.get()).as("첫 번째 링크 INSERT는 실제로 실행됐다").isEqualTo(2);
        assertThat(countRows("courses", "title = '" + TITLE_PREFIX + "운영체제'"))
                .as("먼저 만든 프로젝트도 롤백된다").isZero();
        assertThat(countRows("material_links", "material_id = " + first))
                .as("먼저 넣은 링크도 롤백된다").isZero();
    }

    // ===== (c) 동시 apply =====

    @Test
    @DisplayName("(c) 같은 자료를 동시에 적용하면 하나만 성공한다 — 프로젝트가 두 개 생기면 실패다")
    void concurrentApplyOnTheSameMaterialCreatesOnlyOneProject() throws Exception {
        Long materialId = givenUnlinkedMaterial("운영체제 강의계획서");
        Long user = userId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (String title : List.of(TITLE_PREFIX + "먼저", TITLE_PREFIX + "나중")) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    try {
                        service.apply(user, request(createGroup(title, materialId)));
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> result : results) {
                outcomes.add(result.get(20, TimeUnit.SECONDS));
            }

            assertThat(outcomes).filteredOn(java.util.Objects::isNull).as("성공한 요청").hasSize(1);
            assertThat(outcomes).filteredOn(java.util.Objects::nonNull).as("거부된 요청")
                    .singleElement()
                    .isInstanceOf(ConflictException.class);
        } finally {
            pool.shutdownNow();
        }

        assertThat(countRows("courses", "title LIKE '" + TITLE_PREFIX + "%'"))
                .as("같은 자료로 프로젝트가 둘 생기면 안 된다").isEqualTo(1);
        assertThat(countRows("material_links", "material_id = " + materialId)).isEqualTo(1);
    }

    @Test
    @DisplayName("정상 적용은 프로젝트를 만들고 연결까지 한 트랜잭션에 끝낸다")
    void appliesGroupsInOneTransaction() {
        Long first = givenUnlinkedMaterial("운영체제 강의계획서");
        Long second = givenUnlinkedMaterial("운영체제 교재 목차");

        LinkProposalApplyResponse response = service.apply(userId(),
                request(createGroup(TITLE_PREFIX + "운영체제", first, second)));

        assertThat(response.getCreatedProjects()).singleElement()
                .satisfies(project -> assertThat(project.title()).isEqualTo(TITLE_PREFIX + "운영체제"));
        assertThat(response.getLinkedMaterialCount()).isEqualTo(2);
        assertThat(countRows("material_links",
                "course_id = " + response.getCreatedProjects().get(0).courseId())).isEqualTo(2);
    }

    // ===== 픽스처 =====

    private LinkProposalApplyRequest request(LinkProposalApplyRequest.ApplyGroup... groups) {
        LinkProposalApplyRequest request = new LinkProposalApplyRequest();
        request.setGroups(List.of(groups));
        return request;
    }

    private LinkProposalApplyRequest.ApplyGroup createGroup(String title, Long... materialIds) {
        LinkProposalApplyRequest.ApplyGroup group = new LinkProposalApplyRequest.ApplyGroup();
        group.setAction(ProposalAction.CREATE_AND_LINK);
        group.setTitle(title);
        group.setMembers(members(materialIds));
        return group;
    }

    private LinkProposalApplyRequest.ApplyGroup linkGroup(Long courseId, Long... materialIds) {
        LinkProposalApplyRequest.ApplyGroup group = new LinkProposalApplyRequest.ApplyGroup();
        group.setAction(ProposalAction.LINK_EXISTING);
        group.setExistingCourseId(courseId);
        group.setMembers(members(materialIds));
        return group;
    }

    private List<LinkProposalApplyRequest.ApplyMember> members(Long... materialIds) {
        return List.of(materialIds).stream().map(materialId -> {
            LinkProposalApplyRequest.ApplyMember member = new LinkProposalApplyRequest.ApplyMember();
            member.setMaterialId(materialId);
            member.setMaterialType(MaterialType.SYLLABUS);
            return member;
        }).toList();
    }

    /** ACTIVE · 추출 성공 · 어느 프로젝트에도 연결되지 않은 자료 하나. */
    private Long givenUnlinkedMaterial(String extractedText) {
        String filename = FILENAME_PREFIX + System.nanoTime() + ".pdf";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO course_materials
                         (user_id, original_filename, stored_filename, storage_path,
                          content_type, size_bytes, extraction_status, extracted_text, status)
                     VALUES (?, ?, ?, ?, 'application/pdf', 1024, 'SUCCESS', ?, 'ACTIVE')
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId());
            ps.setString(2, filename);
            ps.setString(3, filename);
            ps.setString(4, userId() + "/" + filename);
            ps.setString(5, extractedText);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                Long materialId = keys.getLong(1);
                createdMaterialIds.add(materialId);
                return materialId;
            }
        } catch (Exception e) {
            throw new IllegalStateException("테스트 자료 준비 실패", e);
        }
    }

    private Long userId() {
        if (userId != null) {
            return userId;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM users ORDER BY user_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("users 테이블이 비어 있어 테스트할 수 없다");
            }
            userId = rs.getLong(1);
            return userId;
        } catch (Exception e) {
            throw new IllegalStateException("테스트 준비 실패", e);
        }
    }

    private int countRows(String table, String where) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + where);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException("행 수 확인 실패", e);
        }
    }

    private void exec(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        }
    }
}
