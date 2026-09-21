package com.jungwoo.project.memo.learning.tidy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 이번 정리가 <b>실제로 무엇을 보았는가</b>. scope_json으로 저장되고 화면에 그대로 나간다.
 *
 * <p>이 기록이 필요한 이유: 입력 한도 때문에 일부만 읽고도 "전체 자료를 반영했다"고 말하면
 * 사용자는 없는 근거를 믿게 된다. 무엇을 뺐는지, 왜 뺐는지, 몇 구간을 못 보았는지를 같이
 * 저장하고 화면이 "부분 정리"라고 말한다.
 *
 * @param truncated      한도에 걸려 일부 구간을 싣지 못했다
 * @param treeLinesShown 모델에게 보여 준 학습 구조 줄 수. topicCount보다 적으면 트리도 일부만 보았다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectTidyScope(
        Long courseId,
        Long treeVersion,
        int topicCount,
        int treeLinesShown,
        List<Member> reviewed,
        List<Excluded> excluded,
        boolean truncated,
        int sectionsTotal,
        int sectionsReviewed
) {

    /** 이번에 실제로 읽은 자료. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(Long materialId, String filename, String fileHash, Integer analysisVersion,
                         int sectionCount, int reviewedCount) {
        /** 구간을 전부 싣지 못했다. */
        public boolean partial() {
            return reviewedCount < sectionCount;
        }
    }

    /** 이번에 뺀 자료와 사유. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Excluded(Long materialId, String filename, String reason, String reasonLabel) {
    }

    public static ProjectTidyScope empty(Long courseId, Long treeVersion) {
        return new ProjectTidyScope(courseId, treeVersion, 0, 0, List.of(), List.of(), false, 0, 0);
    }
}
