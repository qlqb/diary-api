package com.jungwoo.project.memo.learning.tidy.dto;

import com.jungwoo.project.memo.learning.tidy.ProjectTidyScope;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 프로젝트 정리 화면이 필요한 전부. 정리안이 없을 때도 이 모양으로 온다(요청 전/생성 중/실패).
 *
 * <p>변경을 <b>영향을 받는 항목</b>으로 묶어 준다(groups). 파일별 카드가 아니다 — 사용자가 보는
 * 것은 "스택에 강의·교재가 붙고 중복된 큐가 합쳐진다"이지 "3번 파일의 제안"이 아니다.
 */
@Getter
@Builder(toBuilder = true)
public class ProjectTidyResponse {

    private Long courseId;
    /** 지금 검토할 정리안이 있으면 그 상태, 없으면 null. */
    private Long proposalId;
    /** PROPOSED / APPLIED / DISMISSED / SUPERSEDED / EMPTY */
    private String status;
    private Long revision;
    private Long baseTreeVersion;
    private Long currentTreeVersion;
    /**
     * 이 정리안을 만든 뒤 트리가 바뀌었다. 그대로 적용하지 않고 다시 정리해야 한다.
     * 화면은 정리안을 숨기지 않고 "갱신 필요"로 표시한다.
     */
    private boolean treeChanged;

    /** 만드는 중이거나 실패한 작업. 없으면 null. */
    private Job job;

    private Summary summary;
    private List<Group> groups;
    private List<Change> changes;
    /** changeId → 함께 골라야 하는 changeId들. 화면이 체크를 연동하는 데 쓴다. */
    private Map<String, List<String>> dependsOn;

    /** 사용자가 고친 것(제목·제외). 저장된 값 그대로. */
    private Map<String, Edit> edits;
    private Long editRevision;

    /** 이번 정리가 실제로 무엇을 보았는가. 부분 정리면 화면이 그 말을 한다. */
    private ProjectTidyScope scope;

    /**
     * 이 정리안을 만든 뒤 분석이 끝난 자료 수. 0보다 크면 화면이 "새 자료 N개를 반영할 수 있어요"와
     * [새 자료 반영해 다시 정리]를 보여준다. 몰래 섞지 않는다.
     */
    private int newMaterialCount;
    /** 지금 정리에 쓸 수 있는 자료 수(요청 전 미리보기와 같은 값). */
    private int readyMaterialCount;
    private int analyzingMaterialCount;
    /** 아직 한 번도 정리하지 않은 프로젝트인가. 화면 문구가 달라진다. */
    private boolean firstTime;
    /** 이전 방식(자료별)으로 만들어졌다가 물러난 변경안 수. 전환 안내에 쓴다. */
    private int legacyProposalCount;

    /**
     * 요청한 일이 <이미> 끝나 있었다. 폐기를 눌렀는데 그 사이 적용이 끝난 경우 등.
     *
     * <p>실패가 아니다 — 사용자가 원한 결과(검토할 안이 없다)는 이뤄졌다. 다만 화면이
     * "방금 내가 버렸다"와 "이미 처리돼 있었다"를 구분해 말할 수 있어야 한다.
     */
    private Boolean alreadyResolved;

    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;

    @Getter
    @Builder
    public static class Job {
        private Long jobId;
        /** QUEUED / RUNNING / DONE / FAILED / UNAVAILABLE / CANCELLED */
        private String status;
        private String errorCode;
        private String message;
        private LocalDateTime createdAt;
        private boolean retryable;
    }

    @Getter
    @Builder
    public static class Summary {
        private String headline;
        private int link;
        private int add;
        private int rename;
        private int move;
        private int merge;
        private int split;
        private int total;
        /** 이동·병합·분할이 있다. 학습 기록에 영향이 있으므로 접지 않고 보여준다. */
        private boolean structural;
        private int reviewedMaterialCount;
        private int excludedMaterialCount;
    }

    /** 영향을 받는 항목 하나와 거기 걸린 변경들. */
    @Getter
    @Builder
    public static class Group {
        private String key;
        /** EXISTING(기존 항목) / NEW(새로 만드는 항목) */
        private String kind;
        private Long topicId;
        private String title;
        /** 기존 항목의 부모 제목. 어디에 있는 항목인지 알아야 판단할 수 있다. */
        private String parentTitle;
        private List<String> changeIds;
    }

    @Getter
    @Builder
    public static class Change {
        private String changeId;
        /** LINK / ADD / RENAME / MOVE / MERGE / SPLIT */
        private String op;
        private String label;
        /** 사람이 읽는 한 줄. "「스택」에 강의 슬라이드 p.3~5, 교재 2장을 연결해요" */
        private String text;
        /** 모델이 적은 근거. */
        private String reason;
        /** ADD·RENAME만 고칠 수 있다. */
        private boolean titleEditable;
        private String title;
        private boolean structural;
        /** 이 변경이 기대는 다른 변경. 화면이 체크를 연동한다. */
        private List<String> dependsOn;
        /** 학습 기록 승계가 애매할 때 미리 알리는 말. */
        private String caution;
        private List<Section> sections;
    }

    /** 근거 구간. 어느 자료의 어디인지까지 있어야 원문을 열 수 있다. */
    @Getter
    @Builder
    public static class Section {
        private Long sectionId;
        private Long materialId;
        private String materialFilename;
        private String locator;
        private String title;
        private List<String> roles;
        private String taskText;
        private String excerpt;
    }

    @Getter
    @Builder
    public static class Edit {
        private boolean excluded;
        private String title;
        /**
         * 판이 바뀌면서 이 편집이 옮겨 왔는데 대응이 확실하지 않다. 화면이 "확인 필요"를 붙인다.
         */
        private boolean needsConfirm;
    }
}
