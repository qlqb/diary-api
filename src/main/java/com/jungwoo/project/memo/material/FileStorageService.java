package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 업로드 파일을 로컬 디스크에 저장한다(개발 환경 기준). DB에는 메타데이터만 남긴다.
 *
 * 저장 경로는 {uploadDir}/{userId}/{storedFilename}이다 — courseId가 들어가지 않는다.
 * 자료는 프로젝트에 종속되지 않고, 프로젝트 없이도 올릴 수 있어야 하기 때문이다.
 * 호출부는 반환된 storagePath(= uploadDir 기준 상대 경로)를 DB에 그대로 저장하고,
 * 이후 조회는 그 문자열로만 한다 — userId/courseId로 경로를 다시 추론하지 않는다.
 *
 * 검증: 크기 제한, 허용 확장자만, 원본 파일명은 저장 경로에 절대 쓰지 않고 UUID로
 * 대체한다(경로 조작 방지). 소유권은 호출부에서 확인한다.
 */
@Slf4j
@Component
public class FileStorageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "pptx");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    @Value("${storage.materials.upload-dir}")
    private String uploadDir;

    @Value("${storage.materials.max-file-size-bytes}")
    private long maxFileSizeBytes;

    /**
     * @param storagePath uploadDir 기준 상대 경로. 구분자는 항상 '/'다(OS에 의존하지 않는다).
     * @param fileHash    SHA-256 hex. 저장 스트림을 흘려보내며 계산한 값이다.
     */
    public record StoredFile(String storedFilename, String storagePath, String extension, String fileHash) {
    }

    public StoredFile store(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(ErrorCode.EMPTY_FILE);
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new BadRequestException(ErrorCode.FILE_TOO_LARGE);
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BadRequestException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BadRequestException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        String storedFilename = UUID.randomUUID() + "." + extension;
        String storagePath = userId + "/" + storedFilename;
        Path userDir = Path.of(uploadDir, String.valueOf(userId)).normalize();
        Path target = userDir.resolve(storedFilename).normalize();
        if (!target.startsWith(userDir)) {
            // 방어적 체크: storedFilename은 UUID라 실제로 벗어날 수 없지만, 원칙을 코드로도 강제한다.
            throw new BadRequestException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        // 최대 20MB이므로 파일 전체를 메모리에 올리지 않는다 — getBytes()/readAllBytes()를 쓰지 않고
        // 저장 스트림을 흘려보내며 해시를 누적한다.
        MessageDigest digest = sha256();
        try {
            Files.createDirectories(userDir);
            try (InputStream in = file.getInputStream();
                 DigestInputStream hashing = new DigestInputStream(in, digest)) {
                Files.copy(hashing, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("파일 저장 실패: userId={}, storagePath={}", userId, storagePath, e);
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String fileHash = HexFormat.of().formatHex(digest.digest());

        log.info("파일 저장 완료: userId={}, storagePath={}, size={}", userId, storagePath, file.getSize());
        return new StoredFile(storedFilename, storagePath, extension, fileHash);
    }

    /** storagePath는 DB에 저장된 값을 그대로 넘긴다 — 호출부가 경로를 조립하지 않는다. */
    public Path resolve(String storagePath) {
        Path root = Path.of(uploadDir).normalize();
        Path target = root.resolve(storagePath).normalize();
        if (!target.startsWith(root)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return target;
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 JDK 표준 필수 알고리즘이라 실제로 발생하지 않는다.
            throw new IllegalStateException(e);
        }
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "";
        }
        String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1);
        return ext.toLowerCase(Locale.ROOT);
    }
}
