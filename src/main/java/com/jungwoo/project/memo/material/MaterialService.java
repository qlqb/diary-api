package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.learning.structure.TopicChangeProposalService;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import com.jungwoo.project.memo.material.domain.AnalysisJobKind;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.dto.MaterialLinkResponse;
import com.jungwoo.project.memo.material.dto.MaterialResponse;
import com.jungwoo.project.memo.material.dto.MaterialStoreItemResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 학습 자료 업로드 오케스트레이션: 소유권 검증 -> 디스크 저장 -> 텍스트 추출 -> 메타데이터 저장.
 *
 * Material Agent 분석은 이 서비스가 만든 CourseMaterial(extractedText)을 입력으로만 쓰고,
 * 여기서는 AI를 호출하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialService {

    private final CourseService courseService;
    private final CourseMapper courseMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialLinkMapper materialLinkMapper;
    private final MaterialTxService materialTxService;
    private final FileStorageService fileStorageService;
    private final TextExtractionService textExtractionService;
    private final MaterialTextUnitService materialTextUnitService;
    private final MaterialAnalysisJobService analysisJobService;
    private final TopicChangeProposalService topicChangeProposalService;

    /**
     * 업로드 순서가 중요하다: 파일 저장 -> 텍스트 추출 -> [트랜잭션: material + link INSERT].
     *
     * DB 쓰기를 마지막에 두면 실패했을 때 남는 것은 "아무도 참조하지 않는 디스크 파일"뿐이고,
     * 이건 로그만 남기면 되는 무해한 상태다. 반대로 INSERT를 먼저 커밋하고 파일을 쓰면
     * "status=ACTIVE인데 원본이 없는 자료"가 남는데, 그건 삭제 규칙이 금지하는 상태다.
     * (삭제는 정반대로 DB 먼저다 — 양쪽 다 실패 시 고아 파일이 남는 쪽으로 통일한다.)
     *
     * 추출을 트랜잭션 앞에 두면 extraction 결과를 처음부터 알고 INSERT하므로 별도 UPDATE가
     * 필요 없다.
     */
    public MaterialResponse upload(Long userId, Long courseId, MaterialType materialType, MultipartFile file) {
        if (courseId != null) {
            courseService.getOwned(userId, courseId);
        }

        FileStorageService.StoredFile stored = fileStorageService.store(userId, file);

        Path savedPath = fileStorageService.resolve(stored.storagePath());
        TextExtractionService.ExtractionResult result = textExtractionService.extract(savedPath, stored.extension());
        // 페이지·슬라이드 단위. 전체 텍스트와 별개로, 구간 분석이 "몇 페이지"를 말하기 위한 원본이다.
        MaterialTextUnitService.Extracted units = result.status() == ExtractionStatus.SUCCESS
                ? materialTextUnitService.extractUnits(savedPath, stored.extension(), userId, null, stored.fileHash())
                : new MaterialTextUnitService.Extracted(List.of(), null);

        CourseMaterial material = CourseMaterial.builder()
                .userId(userId)
                .originalFilename(sanitizeDisplayName(file.getOriginalFilename()))
                .storedFilename(stored.storedFilename())
                .storagePath(stored.storagePath())
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .pageCount(units.pageCount())
                .fileHash(stored.fileHash())
                .extractionStatus(result.status())
                .extractedText(result.text())
                .extractionError(result.error())
                .status(MaterialStatus.ACTIVE)
                .build();
        materialTxService.createWithLink(material, courseId, materialType, units.units());

        log.info("자료 업로드 완료: userId={}, courseId={}, materialId={}, extractionStatus={}, units={}",
                userId, courseId, material.getMaterialId(), result.status(), units.units().size());

        // 자동 분석 등록. 커밋 뒤라 worker가 바로 집어도 자료 행이 보인다. 등록 실패는 업로드 실패가
        // 아니다 — 폴러의 backlog 등록이 같은 자료를 다시 잡는다.
        try {
            analysisJobService.enqueueContent(material, MaterialAnalysisJobService.PRIORITY_NEW_UPLOAD);
        } catch (Exception e) {
            log.warn("업로드 후 분석 등록 실패(backlog가 다시 시도): materialId={}", material.getMaterialId(), e);
        }

        return MaterialResponse.of(material, courseId, materialType);
    }

    @Transactional(readOnly = true)
    public List<MaterialResponse> listByCourse(Long userId, Long courseId) {
        courseService.getOwned(userId, courseId);
        // materialType은 자료가 아니라 링크가 갖는다 — 같은 파일이 프로젝트마다 다른 성격일 수 있다.
        Map<Long, MaterialType> typeByMaterialId = materialLinkMapper.findByCourseIdAndUserId(courseId, userId)
                .stream()
                .collect(Collectors.toMap(MaterialLink::getMaterialId, MaterialLink::getMaterialType,
                        (a, b) -> a));
        return courseMaterialMapper.findByCourseIdAndUserId(courseId, userId).stream()
                .map(m -> MaterialResponse.of(m, courseId, typeByMaterialId.get(m.getMaterialId())))
                .toList();
    }

    /** 전역 자료함 목록. 각 자료에 연결된 프로젝트를 함께 싣는다. */
    @Transactional(readOnly = true)
    public List<MaterialStoreItemResponse> listAll(Long userId) {
        List<CourseMaterial> materials = courseMaterialMapper.findAllByUserId(userId);
        if (materials.isEmpty()) {
            return List.of();
        }
        List<MaterialLink> links = materialLinkMapper.findByUserId(userId);
        Map<Long, String> titleByCourseId = courseTitles(userId, links);
        Map<Long, List<MaterialLinkResponse>> linksByMaterialId = links.stream()
                .collect(Collectors.groupingBy(MaterialLink::getMaterialId,
                        Collectors.mapping(l -> MaterialLinkResponse.of(l, titleByCourseId.get(l.getCourseId())),
                                Collectors.toList())));
        return materials.stream()
                .map(m -> MaterialStoreItemResponse.of(m,
                        linksByMaterialId.getOrDefault(m.getMaterialId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public MaterialStoreItemResponse getStoreItem(Long userId, Long materialId) {
        CourseMaterial material = getActiveOwned(userId, materialId);
        List<MaterialLink> links = materialLinkMapper.findByMaterialIdAndUserId(materialId, userId);
        Map<Long, String> titleByCourseId = courseTitles(userId, links);
        return MaterialStoreItemResponse.of(material, links.stream()
                .map(l -> MaterialLinkResponse.of(l, titleByCourseId.get(l.getCourseId())))
                .toList());
    }

    /**
     * 자료 삭제. 트랜잭션을 여기서 열지 않는다 — DB 커밋이 끝난 뒤에 파일을 지워야 하기
     * 때문이다. 업로드와 정반대 순서이고, 양쪽 다 실패 시 고아 파일이 남는 쪽으로 통일한다.
     *
     * markDeleted를 다른 빈(MaterialTxService)으로 부르는 것이 핵심이다. 같은 클래스 안에서
     * this.markDeleted()를 부르면 Spring 프록시를 타지 않아 @Transactional이 아예 걸리지
     * 않고, 그러면 "커밋 후 파일 삭제"라는 이 순서 자체가 성립하지 않는다.
     */
    public void delete(Long userId, Long materialId) {
        CourseMaterial material = materialTxService.markDeleted(userId, materialId);
        // 늦게 끝난 worker가 지운 자료의 토픽·과제를 되살리지 못하게: 열린 작업 취소(임대 토큰이 바뀌어
        // 진행 중인 결과 저장도 0행이 된다), 열린 변경안은 STALE.
        analysisJobService.cancelForMaterial(userId, materialId);
        topicChangeProposalService.staleFor(userId, materialId, null);
        fileStorageService.deleteQuietly(materialId, material.getStoragePath());
    }

    /**
     * 자료를 프로젝트에 연결한다. materialType은 업로드가 아니라 이 시점에 정해진다.
     *
     * 보관(ARCHIVED)된 프로젝트는 연결 후보 목록에서부터 빠지지만(GET /courses가 ACTIVE만
     * 돌려준다), 그건 UI 편의일 뿐이고 여기서도 다시 막는다 — courseId를 직접 지정해 호출하는
     * 경로까지 막아야 "보관한 프로젝트에 새로 연결할 수 없다"는 규칙이 실제로 지켜진다.
     */
    @Transactional
    public MaterialLinkResponse addLink(Long userId, Long materialId, Long courseId, MaterialType materialType) {
        getActiveOwned(userId, materialId);
        Course course = courseService.getOwned(userId, courseId);
        if (course.getStatus() == CourseStatus.ARCHIVED) {
            throw new ConflictException(ErrorCode.COURSE_ARCHIVED);
        }
        if (materialLinkMapper.findByMaterialIdAndCourseIdAndUserId(materialId, courseId, userId) != null) {
            throw new ConflictException(ErrorCode.MATERIAL_ALREADY_LINKED_TO_COURSE);
        }
        MaterialLink link = MaterialLink.builder()
                .userId(userId).materialId(materialId).courseId(courseId)
                .materialType(materialType)
                .build();
        materialLinkMapper.insert(link);
        log.info("자료 연결: userId={}, materialId={}, courseId={}, type={}",
                userId, materialId, courseId, materialType);
        enqueueLinkIfContentReady(userId, materialId, courseId);
        return MaterialLinkResponse.of(link, course.getTitle());
    }

    /**
     * 링크의 역할(materialType)만 바꾼다. addLink와 같은 규칙을 따른다 — 자료 ACTIVE,
     * 프로젝트 ACTIVE, 링크 존재.
     *
     * 보관된 프로젝트를 여기서도 막는 이유: addLink가 이미 ARCHIVED 프로젝트로의 신규 연결을
     * 409로 막고 있는데 역할 변경만 열어두면 "보관된 프로젝트는 읽기 전용"이라는 규칙이
     * 반쪽이 된다. 다시 꺼낸(복원한) 뒤에는 그대로 바꿀 수 있다.
     */
    @Transactional
    public MaterialLinkResponse updateLinkType(Long userId, Long materialId, Long courseId, MaterialType materialType) {
        getActiveOwned(userId, materialId);
        Course course = courseService.getOwned(userId, courseId);
        if (course.getStatus() == CourseStatus.ARCHIVED) {
            throw new ConflictException(ErrorCode.COURSE_ARCHIVED);
        }
        MaterialLink link = getRequiredLink(userId, materialId, courseId);
        materialLinkMapper.updateMaterialType(materialId, courseId, userId, materialType);
        link.setMaterialType(materialType);
        log.info("자료 역할 변경: userId={}, materialId={}, courseId={}, type={}",
                userId, materialId, courseId, materialType);
        return MaterialLinkResponse.of(link, course.getTitle());
    }

    /**
     * 연결 해제. 이 프로젝트에서 더 이상 이 자료를 참고하지 않겠다는 뜻일 뿐이다.
     *
     * 자료 원본도, 다른 프로젝트 연결도, 분석 이력도, 이미 apply된 course_topics /
     * course_notes도 건드리지 않는다. 확정된 학습 내용은 Source에서 파생됐더라도 사용자가
     * 확정한 프로젝트 상태이지, 연결의 부산물이 아니다.
     */
    @Transactional
    public void removeLink(Long userId, Long materialId, Long courseId) {
        getRequiredLink(userId, materialId, courseId);
        materialLinkMapper.delete(materialId, courseId, userId);
        // 이 프로젝트 맥락의 분석은 더 이상 유효하지 않다. 원문 분석(CONTENT)과 다른 프로젝트 연결은 남는다.
        analysisJobService.cancelLinkJobs(userId, materialId, courseId);
        topicChangeProposalService.staleFor(userId, materialId, courseId);
        log.info("자료 연결 해제: userId={}, materialId={}, courseId={}", userId, materialId, courseId);
    }

    /**
     * 프로젝트에 연결되는 순간, 원문 분석이 이미 끝나 있으면 그 결과를 재사용해 연결 변경안(LINK)만 만든다.
     * 아직이면 폴러가 CONTENT 완료 뒤에 LINK를 등록한다(registerLinkBacklog).
     */
    private void enqueueLinkIfContentReady(Long userId, Long materialId, Long courseId) {
        try {
            CourseMaterial material = courseMaterialMapper.findByIdAndUserId(materialId, userId);
            if (material == null || material.getFileHash() == null) {
                return;
            }
            for (MaterialAnalysisJob job : analysisJobService.findByMaterials(userId, List.of(materialId))) {
                if (job.getJobKind() == AnalysisJobKind.CONTENT && material.getFileHash().equals(job.getFileHash())
                        && job.getStatus() != null && job.getStatus().hasUsableResult()) {
                    analysisJobService.enqueueLink(userId, materialId, courseId, material.getFileHash(),
                            MaterialAnalysisJobService.PRIORITY_NEW_UPLOAD);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("연결 후 LINK 등록 실패(backlog가 다시 시도): materialId={}, courseId={}", materialId, courseId, e);
        }
    }

    private Map<Long, String> courseTitles(Long userId, List<MaterialLink> links) {
        List<Long> courseIds = links.stream().map(MaterialLink::getCourseId).distinct().toList();
        if (courseIds.isEmpty()) {
            return Map.of();
        }
        return courseMapper.findByIdsAndUserId(courseIds, userId).stream()
                .collect(Collectors.toMap(Course::getCourseId, Course::getTitle, (a, b) -> a));
    }

    /** ACTIVE 자료만. 목록·상세·업로드 후속·분석·AI·링크 생성 — 기본 경로 전부 이걸 쓴다. */
    @Transactional(readOnly = true)
    public CourseMaterial getActiveOwned(Long userId, Long materialId) {
        CourseMaterial material = courseMaterialMapper.findByIdAndUserId(materialId, userId);
        if (material == null) {
            throw new NotFoundException(ErrorCode.COURSE_MATERIAL_NOT_FOUND);
        }
        return material;
    }

    /**
     * 원본 파일 자체를 열어 준다. 화면에서 "PDF 열기"가 이 경로를 쓴다.
     *
     * 지금까지 원본은 업로드된 뒤 텍스트 추출에만 쓰였고 사용자가 다시 볼 길이 없었다.
     * 자료 이름만 보이는 자리에서 "그래서 그 파일이 뭐였더라"를 확인하려면 자료함을 떠나
     * 원래 파일을 찾아야 했다.
     *
     * DELETED 자료는 여기로 오지 않는다 — getActiveOwned가 막는다. 삭제는 디스크 파일까지
     * 지우므로 열 원본이 실제로 없고, provenance 표시(findForProvenance)는 이름만 쓴다.
     */
    @Transactional(readOnly = true)
    public MaterialFile openFile(Long userId, Long materialId) {
        CourseMaterial material = getActiveOwned(userId, materialId);
        if (material.getStoragePath() == null) {
            throw new NotFoundException(ErrorCode.MATERIAL_FILE_NOT_FOUND);
        }
        Path path = fileStorageService.resolve(material.getStoragePath());
        if (!Files.isReadable(path)) {
            log.error("자료의 원본 파일이 없음: materialId={}, storagePath={}",
                    materialId, material.getStoragePath());
            throw new NotFoundException(ErrorCode.MATERIAL_FILE_NOT_FOUND);
        }
        return new MaterialFile(
                new FileSystemResource(path),
                sanitizeDisplayName(material.getOriginalFilename()),
                material.getContentType(),
                material.getSizeBytes());
    }

    /** 응답에 필요한 것만 — 컨트롤러가 CourseMaterial 전체를 만지지 않게 한다. */
    public record MaterialFile(Resource resource, String filename, String contentType, Long sizeBytes) {
    }

    /**
     * DELETED도 포함해 자료를 찾는다. provenance 표시 전용 — 그 외 경로에서 쓰지 않는다.
     *
     * 삭제된 자료도 반환하는 유일한 경로다. 원본을 지워도 course_topics.source_material_id는
     * 그대로 남기 때문에, "이 학습 항목이 어디서 왔는지"를 계속 말해줄 수 있어야 한다.
     * 파일 내용에는 접근하지 않는다 — 이름과 삭제 여부만 쓴다.
     */
    @Transactional(readOnly = true)
    public Map<Long, CourseMaterial> findForProvenance(Long userId, List<Long> materialIds) {
        List<Long> ids = materialIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(ids, userId).stream()
                .collect(Collectors.toMap(CourseMaterial::getMaterialId, m -> m, (a, b) -> a));
    }

    /**
     * 이 자료가 이 프로젝트에 실제로 연결되어 있는지 확인하고 링크를 돌려준다.
     *
     * 자료가 course에 종속돼 있을 때는 소유권 검증만으로 충분했지만, 전역이 되는 순간
     * 연결되지 않은 자료를 임의의 프로젝트 맥락에서 다룰 수 있는 구멍이 생긴다.
     */
    @Transactional(readOnly = true)
    public MaterialLink getRequiredLink(Long userId, Long materialId, Long courseId) {
        MaterialLink link = materialLinkMapper.findByMaterialIdAndCourseIdAndUserId(materialId, courseId, userId);
        if (link == null) {
            throw new NotFoundException(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE);
        }
        return link;
    }

    /** 경로 조작 문자를 제거한 표시용 원본 파일명. 실제 저장 경로에는 쓰지 않는다(storagePath만 사용). */
    private String sanitizeDisplayName(String originalFilename) {
        if (originalFilename == null) {
            return "unknown";
        }
        String name = originalFilename.replaceAll("[\\\\/\\x00]", "_");
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
