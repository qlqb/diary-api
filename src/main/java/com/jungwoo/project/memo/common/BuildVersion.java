package com.jungwoo.project.memo.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 지금 돌고 있는 서버가 어느 커밋인가. 계획 생성 근거 기록에 남겨 "개발한 기능"과 "실제로 평가에 쓴 서버"를 구분한다
 * (2026-09-19 진단에서 새 기능이 평가 서버에 없었던 일이 있었다).
 *
 * <p>APP_COMMIT 환경 변수가 있으면 그 값, 없으면 작업 디렉터리의 .git에서 HEAD를 읽는다(worktree의 "gitdir:" 파일 포함).
 * git을 실행하지 않는다 — 파일만 읽는다. 알 수 없으면 null이다(배포 환경 등).
 */
@Slf4j
@Component
public class BuildVersion {

    private final String commit;

    public BuildVersion(@Value("${app.commit:${APP_COMMIT:}}") String configured) {
        String resolved = configured == null || configured.isBlank() ? readGitHead(Path.of("").toAbsolutePath()) : configured.trim();
        this.commit = resolved;
        log.info("서버 빌드 커밋: {}", resolved == null ? "(알 수 없음)" : resolved);
    }

    public String commit() {
        return commit;
    }

    static String readGitHead(Path workDir) {
        try {
            Path dotGit = workDir.resolve(".git");
            if (!Files.exists(dotGit)) {
                return null;
            }
            Path gitDir = dotGit;
            if (Files.isRegularFile(dotGit)) {
                String pointer = Files.readString(dotGit, StandardCharsets.UTF_8).trim();
                if (!pointer.startsWith("gitdir:")) {
                    return null;
                }
                gitDir = workDir.resolve(pointer.substring("gitdir:".length()).trim()).normalize();
            }
            String head = Files.readString(gitDir.resolve("HEAD"), StandardCharsets.UTF_8).trim();
            if (!head.startsWith("ref:")) {
                return shorten(head);
            }
            String ref = head.substring("ref:".length()).trim();
            Path refFile = gitDir.resolve(ref);
            if (!Files.exists(refFile)) {
                // worktree의 브랜치 ref는 공용 디렉터리(commondir)에 있다.
                Path commonPointer = gitDir.resolve("commondir");
                if (Files.exists(commonPointer)) {
                    Path common = gitDir.resolve(Files.readString(commonPointer, StandardCharsets.UTF_8).trim()).normalize();
                    refFile = common.resolve(ref);
                }
            }
            return Files.exists(refFile) ? shorten(Files.readString(refFile, StandardCharsets.UTF_8).trim()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String shorten(String sha) {
        return sha == null || sha.length() < 7 ? null : sha.substring(0, Math.min(12, sha.length()));
    }
}
