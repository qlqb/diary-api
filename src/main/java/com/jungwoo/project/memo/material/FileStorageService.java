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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 업로드 파일을 로컬 디스크에 저장한다(개발 환경 기준). DB에는 메타데이터만 남긴다.
 *
 * 검증: 소유자 자신의 courseId 하위에만 저장(호출부에서 소유권 확인), 크기 제한,
 * 허용 확장자만, 원본 파일명은 저장 경로에 절대 쓰지 않고 UUID로 대체한다(경로 조작 방지).
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

    public record StoredFile(String storedFilename, String extension) {
    }

    public StoredFile store(Long userId, Long courseId, MultipartFile file) {
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
        Path courseDir = Path.of(uploadDir, String.valueOf(userId), String.valueOf(courseId));
        Path target = courseDir.resolve(storedFilename).normalize();
        if (!target.startsWith(courseDir.normalize())) {
            // 방어적 체크: storedFilename은 UUID라 실제로 벗어날 수 없지만, 원칙을 코드로도 강제한다.
            throw new BadRequestException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        try {
            Files.createDirectories(courseDir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("파일 저장 실패: userId={}, courseId={}", userId, courseId, e);
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        log.info("파일 저장 완료: userId={}, courseId={}, storedFilename={}, size={}",
                userId, courseId, storedFilename, file.getSize());
        return new StoredFile(storedFilename, extension);
    }

    public Path resolve(Long userId, Long courseId, String storedFilename) {
        return Path.of(uploadDir, String.valueOf(userId), String.valueOf(courseId), storedFilename);
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "";
        }
        String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1);
        return ext.toLowerCase(Locale.ROOT);
    }
}
