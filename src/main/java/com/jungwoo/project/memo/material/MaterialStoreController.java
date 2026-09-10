package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.material.dto.LinkProposalApplyRequest;
import com.jungwoo.project.memo.material.dto.LinkProposalApplyResponse;
import com.jungwoo.project.memo.material.dto.LinkProposalRequest;
import com.jungwoo.project.memo.material.dto.LinkProposalResponse;
import com.jungwoo.project.memo.material.dto.MaterialDetailResponse;
import com.jungwoo.project.memo.material.dto.MaterialLinkCreateRequest;
import com.jungwoo.project.memo.material.dto.MaterialLinkResponse;
import com.jungwoo.project.memo.material.dto.MaterialLinkTypeUpdateRequest;
import com.jungwoo.project.memo.material.dto.MaterialResponse;
import com.jungwoo.project.memo.material.dto.MaterialStoreItemResponse;
import com.jungwoo.project.memo.material.linkproposal.MaterialLinkProposalService;
import com.jungwoo.project.memo.material.linkproposal.ProposalTrigger;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 전역 자료함. 자료는 프로젝트가 아니라 사용자가 소유한다.
 *
 * GET    /api/materials                        내 전체 자료 + 각 자료에 연결된 프로젝트
 * POST   /api/materials                        프로젝트 없이 업로드 (materialType을 받지 않는다)
 * GET    /api/materials/{id}                   단건 + 연결 목록 + 분석 이력
 * GET    /api/materials/{id}/file              원본 파일 (inline)
 * DELETE /api/materials/{id}                   자료 삭제 (원본 파일까지)
 * POST   /api/materials/link-proposal          미연결 자료를 어디에 넣을지 제안 (저장하지 않는다)
 * POST   /api/materials/link-proposal/apply    승인한 제안을 적용 (단일 트랜잭션)
 * POST   /api/materials/{id}/links             프로젝트에 연결. 이때 materialType이 정해진다
 * PATCH  /api/materials/{id}/links/{courseId}   그 프로젝트에서의 역할(materialType)만 변경
 * DELETE /api/materials/{id}/links/{courseId}  연결 해제만 (자료는 남는다)
 *
 * 프로젝트 화면의 업로드/목록은 기존 /api/courses/{courseId}/materials가 그대로 담당한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
public class MaterialStoreController {

    private final MaterialService materialService;
    private final MaterialAnalysisService materialAnalysisService;
    private final MaterialLinkProposalService materialLinkProposalService;

    @GetMapping
    public ResponseEntity<List<MaterialStoreItemResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(materialService.listAll(principal.getUserId()));
    }

    /**
     * 프로젝트 없이 업로드한다. materialType을 받지 않는 것이 의도다 — 자료의 성격은
     * "이 프로젝트가 이걸 무엇으로 쓰는가"라서 연결 시점에 정해진다. 그때까지 타입이
     * 없는 것이 정상이고, 그 상태를 결함처럼 표시하지 않는다.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<MaterialResponse> upload(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file
    ) {
        log.info("POST /api/materials - userId={}, filename={}",
                principal.getUserId(), file.getOriginalFilename());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(materialService.upload(principal.getUserId(), null, null, file));
    }

    @GetMapping("/{materialId}")
    public ResponseEntity<MaterialDetailResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long materialId
    ) {
        Long userId = principal.getUserId();
        return ResponseEntity.ok(MaterialDetailResponse.builder()
                .material(materialService.getStoreItem(userId, materialId))
                .analyses(materialAnalysisService.listByMaterial(userId, materialId))
                .build());
    }

    /**
     * 원본 파일을 그대로 내려준다. 자료 이름이 보이는 자리에서 "파일 열기"가 이 경로를 쓴다.
     *
     * inline으로 보내는 이유: PDF는 브라우저가 바로 그려 준다. 다른 형식(pptx)은 inline을
     * 받아도 브라우저가 결국 내려받으므로, 여기서 형식을 갈라 헤더를 다르게 쓰지 않는다.
     * 파일명은 RFC 5987로 인코딩한다 — 한글 파일명이 대부분이라 그냥 넣으면 깨진다.
     *
     * 인증은 다른 경로와 같은 Bearer 토큰이다. 쿼리 문자열 토큰이나 서명 URL을 만들지
     * 않았다 — 만들면 링크 하나가 그대로 유출 경로가 되고, 화면 쪽에서 blob으로 받아 여는
     * 것만으로 충분하다.
     */
    @GetMapping("/{materialId}/file")
    public ResponseEntity<Resource> file(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long materialId
    ) {
        MaterialService.MaterialFile file = materialService.openFile(principal.getUserId(), materialId);
        String encodedName = URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedName)
                .contentType(file.contentType() != null
                        ? MediaType.parseMediaType(file.contentType())
                        : MediaType.APPLICATION_OCTET_STREAM);
        if (file.sizeBytes() != null) {
            response.contentLength(file.sizeBytes());
        }
        return response.body(file.resource());
    }

    /** 원본 파일까지 지운다. 연결 해제와는 다른 액션이다. */
    @DeleteMapping("/{materialId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long materialId
    ) {
        log.info("DELETE /api/materials/{} - userId={}", materialId, principal.getUserId());
        materialService.delete(principal.getUserId(), materialId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 미연결 자료를 어느 프로젝트에 넣을지 제안한다. 아무것도 저장하지 않는다.
     *
     * 모델 호출이 실패해도 200 + status=UNAVAILABLE이다. 5xx를 돌려주면 프론트의 자동 경로가
     * 에러 토스트를 띄우게 되는데, 사용자가 요청하지도 않은 기능의 실패를 알릴 이유가 없다.
     * (사용량 한도 초과만은 예외다 — 기존 정책대로 429가 나간다. 다시 눌러도 같은 결과라
     * 재시도를 유도하면 안 되기 때문이다.)
     *
     * 멱등하지 않고 부수효과도 없다. GET이 아닌 이유는 모델 호출 비용이 있어 캐시·프리페치
     * 대상이 되면 안 되기 때문이다.
     */
    @PostMapping("/link-proposal")
    public ResponseEntity<LinkProposalResponse> proposeLinks(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody(required = false) LinkProposalRequest request
    ) {
        List<Long> materialIds = request == null ? null : request.getMaterialIds();
        // trigger는 로그로만 쓴다 — 어느 경로에서 온 호출인지가 없으면 selectable=0을 봐도
        // "자동인데 카드가 안 떴다"인지 "수동이라 카드는 떴다"인지 구분할 수 없다.
        ProposalTrigger trigger = request == null ? null : request.getTrigger();
        return ResponseEntity.ok(materialLinkProposalService.propose(principal.getUserId(), materialIds, trigger));
    }

    /**
     * 사용자가 승인한 묶음을 적용한다. 단일 트랜잭션이라 부분 적용이 없다 —
     * 사용자가 승인한 단위는 화면에 보인 묶음 전체다.
     */
    @PostMapping("/link-proposal/apply")
    public ResponseEntity<LinkProposalApplyResponse> applyLinkProposal(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody LinkProposalApplyRequest request
    ) {
        log.info("POST /api/materials/link-proposal/apply - userId={}, groups={}",
                principal.getUserId(), request.getGroups().size());
        return ResponseEntity.ok(materialLinkProposalService.apply(principal.getUserId(), request));
    }

    @PostMapping("/{materialId}/links")
    public ResponseEntity<MaterialLinkResponse> addLink(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long materialId,
            @Valid @RequestBody MaterialLinkCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(materialService.addLink(
                principal.getUserId(), materialId, request.getCourseId(), request.getMaterialType()));
    }

    /**
     * 이 프로젝트에서 이 자료가 맡는 역할을 바꾼다.
     *
     * 이 경로가 없으면 역할을 고치는 유일한 방법이 "연결 해제 후 재연결"이 되는데, 그건
     * linked_at을 잃고 잠깐이지만 연결이 끊긴 상태를 만든다. 성격 변경은 별도 액션이다.
     */
    @PatchMapping("/{materialId}/links/{courseId}")
    public ResponseEntity<MaterialLinkResponse> updateLinkType(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long materialId,
            @PathVariable Long courseId,
            @Valid @RequestBody MaterialLinkTypeUpdateRequest request
    ) {
        return ResponseEntity.ok(materialService.updateLinkType(
                principal.getUserId(), materialId, courseId, request.getMaterialType()));
    }

    /** 연결만 끊는다. 자료도, 다른 프로젝트 연결도, 이미 적용한 학습 내용도 그대로 남는다. */
    @DeleteMapping("/{materialId}/links/{courseId}")
    public ResponseEntity<Void> removeLink(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long materialId,
            @PathVariable Long courseId
    ) {
        materialService.removeLink(principal.getUserId(), materialId, courseId);
        return ResponseEntity.noContent().build();
    }
}
