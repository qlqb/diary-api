package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.plan.provenance.EvidenceStatus;
import com.jungwoo.project.memo.plan.provenance.ProvenanceRepresentation;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.jungwoo.project.memo.plan.provenance.ServerCalculation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * "이 초안을 만들 때 무엇을 참고했는가"와 "이 항목은 무엇을 근거로 하는가".
 *
 * <p>초안 화면과 적용된 항목 화면이 <b>같은 응답</b>을 쓴다. 화면마다 다른 모양으로
 * 내려주면 같은 사실이 두 곳에서 다르게 보이기 시작한다.
 *
 * <p>★ 세 가지를 한 덩어리로 묶지 않는다. 제공한 원본({@link #providedSources}), 서버가
 * 계산한 것({@link #serverCalculations}), 모델의 추정({@link ItemView#aiEstimates})은
 * 서로 다른 무게를 갖는다.
 *
 * @param recorded      이 제안에 생성 당시 출처 기록이 있는가. false면 화면은 "이 초안에는
 *                      생성 당시 출처 기록이 없습니다"라고 말하고 나머지 필드는 비어 있다
 * @param schemaVersion 저장된 스냅샷의 판. 1판에는 당시 구조·자료 정보가 없어 화면이 자료를
 *                      "현재 연결" 기준으로만 보여준다
 */
public record PlanProvenanceResponse(
        boolean recorded,
        Integer schemaVersion,
        Long proposalId,
        Long planVersionId,
        String generationId,
        LocalDateTime capturedAt,
        String timezone,
        LocalDate startDate,
        LocalDate endDate,
        String generator,
        String modelName,
        List<SourceView> providedSources,
        List<ServerCalculation> serverCalculations,
        List<ItemView> items
) {

    public static PlanProvenanceResponse notRecorded(Long proposalId, Long planVersionId,
                                                     List<ItemView> items) {
        return new PlanProvenanceResponse(false, null, proposalId, planVersionId, null, null, null,
                null, null, null, null, List.of(), List.of(), items);
    }

    /**
     * 제공한 원본 하나.
     *
     * @param providedValue  그때 실제로 준 값. 원본이 나중에 바뀌어도 이 값은 그대로다
     * @param link           지금 원본을 열 수 있는가. 당시 값과 <b>다른 것</b>이라 따로 둔다
     * @param parentSourceId 생성 당시 구조에서의 부모 원본 id(TOPIC의 parent_topic_id). 화면이
     *                       부모·자식을 묶는 유일한 근거다. 1판 스냅샷은 null
     * @param material       이 줄이 나온 자료 파일과 지금 열 수 있는지. 자료 연결이 없으면 null
     */
    public record SourceView(
            String refId,
            ProvenanceSourceType sourceType,
            Long sourceId,
            Long sourceVersion,
            LocalDateTime sourceUpdatedAt,
            ProvenanceRepresentation representation,
            Map<String, Object> providedValue,
            String promptLine,
            LinkView link,
            Long parentSourceId,
            MaterialView material
    ) {
    }

    /**
     * 원본 줄이 나온 자료 파일. <b>당시 기록</b>과 <b>지금 열 수 있는 것</b>을 한 값에 담되
     * 둘을 구분한다 — 지금 파일을 당시 원문으로 가장하지 않는다.
     *
     * @param materialId      지금 열 대상 자료 id. 열 수 없으면 null
     * @param recordedMaterialId 생성 당시 기록된 자료 id(origin=RECORDED). 화면이 같은 자료의 줄을
     *                        묶는 기준이다 — 파일명이 같아도 id가 다르면 다른 자료다. 1판은 null
     * @param filename        당시 파일명(origin=RECORDED) 또는 지금 파일명(origin=CURRENT_LINK)
     * @param currentFilename 지금 연결된 파일명. 교체·재연결됐으면 filename과 다르다
     * @param contentType     지금 파일의 MIME. 화면이 열기/내려받기를 가르는 데 쓴다
     * @param locator         자료 안의 위치 문자열("2주차"). 페이지 번호가 아니다
     * @param origin          RECORDED: 생성 당시 스냅샷에 남은 연결. CURRENT_LINK: 스냅샷에는
     *                        없고(1판) 지금 학습 항목의 연결로만 찾은 자료
     * @param state           지금 이 자료의 상태
     * @param openMode        INLINE: 브라우저가 그대로 연다(PDF). DOWNLOAD: 내려받는다(PPTX 등).
     *                        NONE: 열 수 없다
     * @param note            사용자에게 보일 한 줄. 상태를 색이나 아이콘이 아니라 글로 말한다
     */
    public record MaterialView(
            Long materialId,
            Long recordedMaterialId,
            String filename,
            String currentFilename,
            String contentType,
            String locator,
            MaterialOrigin origin,
            MaterialState state,
            MaterialOpenMode openMode,
            String note
    ) {
        public enum MaterialOrigin { RECORDED, CURRENT_LINK }

        public enum MaterialState {
            /** 당시 파일이 그대로 있다. */
            AVAILABLE,
            /** 같은 자료 id지만 파일 내용(해시)이나 이름이 바뀌었다. 과거 판은 복원할 수 없다. */
            CHANGED,
            /** 학습 항목이 지금은 다른 자료에 연결돼 있다. 당시 자료는 filename에 남는다. */
            RELINKED,
            /** 자료가 지워졌다. 이름만 남는다. */
            DELETED,
            /** 자료 행을 찾을 수 없다. */
            UNAVAILABLE
        }

        public enum MaterialOpenMode { INLINE, DOWNLOAD, NONE }
    }

    /**
     * 원본으로 가는 링크.
     *
     * @param target             화면이 무엇을 열어야 하는지. 열 곳이 없으면 null
     * @param unavailableReason  못 여는 이유. 지워졌거나 애초에 링크가 없는 종류다
     */
    public record LinkView(
            boolean available,
            String target,
            Long targetId,
            String unavailableReason
    ) {

        public static LinkView none(String reason) {
            return new LinkView(false, null, null, reason);
        }

        public static LinkView of(String target, Long targetId) {
            return new LinkView(true, target, targetId, null);
        }
    }

    /**
     * 항목 하나의 근거.
     *
     * @param recorded          이 항목에 근거 기록이 있는가. 레거시 제안은 false다
     * @param unknownRefCount   모델이 인용했지만 이번 회차에 없어 버린 개수. 정상 근거로
     *                          보여주지 않되, 있었다는 사실은 숨기지 않는다
     * @param afterApplyChanges 적용된 뒤 이 조각에 일어난 변경(사용자가 읽는 문장). 근거는
     *                          그대로이고 지금 내용·배치가 그때와 다르다는 뜻이다. 초안
     *                          조회에서는 항상 비어 있다
     */
    public record ItemView(
            Long proposalItemId,
            Long executionItemId,
            String title,
            AiProposalItemStatus status,
            boolean recorded,
            String generationId,
            List<String> refIds,
            String reason,
            List<String> aiEstimates,
            List<String> serverCalculationIds,
            EvidenceStatus evidenceStatus,
            List<String> staleReasons,
            int unknownRefCount,
            List<String> afterApplyChanges
    ) {
    }
}
