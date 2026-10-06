package com.jungwoo.project.memo.material.extract;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 파일 하나를 한 번만 읽어 {@link ExtractedDocument}를 만든다(IPYNB·HWP·HWPX).
 *
 * <p>PDF·PPTX는 기존 경로(TextExtractionService / MaterialTextUnitService)를 그대로 쓴다. 이미
 * 동작하는 추출을 형식 추가를 이유로 다시 쓰지 않는다.
 *
 * <p>읽기는 제한 시간 안에서만 한다. 업로드 요청 스레드가 이상한 파일 하나에 붙잡혀 있으면 그 사용자의
 * 다른 요청까지 막히고, 가져오기 작업자라면 대기열 전체가 멈춘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentExtractionService {

    private static final Set<String> EXTENSIONS = Set.of("ipynb", "hwp", "hwpx", "sh");

    /** 평문 자료 읽기. 상태가 없어 주입하지 않는다. 읽기만 하고 아무것도 실행하지 않는다. */
    private final PlainTextExtractor plainTextExtractor = new PlainTextExtractor();

    private final NotebookExtractor notebookExtractor;
    private final HwpExtractor hwpExtractor;
    private final HwpxExtractor hwpxExtractor;

    /** 파일 하나를 읽는 제한 시간. 넘기면 TIMEOUT으로 실패한다. */
    @Value("${material.extract.timeout-seconds:60}")
    private int timeoutSeconds = 60;

    /**
     * 이 서비스가 쓰는 작업 스레드. 데몬이라 종료를 막지 않는다. 시간 초과 시 interrupt를 보내지만
     * 파서가 즉시 멈춘다는 보장은 없다 — 그래서 크기 상한(파일 크기·컨테이너 상한)이 1차 방어이고,
     * 제한 시간은 2차다.
     */
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "material-extract");
        thread.setDaemon(true);
        return thread;
    });

    public static boolean handles(String extension) {
        return extension != null && EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    public ExtractedDocument extract(Path file, String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return withTimeLimit(() -> switch (ext) {
            case "ipynb" -> notebookExtractor.extract(readAll(file));
            case "hwp" -> hwpExtractor.extract(file);
            case "hwpx" -> hwpxExtractor.extract(file);
            case "sh" -> plainTextExtractor.extract(readAll(file));
            default -> throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "읽는 방법을 모르는 형식이에요: " + ext);
        });
    }

    private byte[] readAll(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (Exception e) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "파일을 읽지 못했어요", e);
        }
    }

    private ExtractedDocument withTimeLimit(Callable<ExtractedDocument> work) {
        Future<ExtractedDocument> future = workers.submit(work);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DocumentExtractionException(DocumentExtractionException.Reason.TIMEOUT);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DocumentExtractionException(DocumentExtractionException.Reason.TIMEOUT);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof DocumentExtractionException known) {
                throw known;
            }
            if (cause instanceof OutOfMemoryError) {
                throw new DocumentExtractionException(DocumentExtractionException.Reason.TOO_LARGE,
                        "문서가 너무 커서 읽지 못했어요", cause);
            }
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CORRUPTED,
                    "문서를 읽는 중 오류가 났어요", cause);
        }
    }
}
