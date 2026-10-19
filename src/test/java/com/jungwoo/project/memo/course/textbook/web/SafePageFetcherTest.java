package com.jungwoo.project.memo.course.textbook.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 외부 링크를 받는 길의 경계. 내부 주소·리다이렉트 우회·느린 서버·큰 본문·압축 폭탄을 막는다.
 * 실제 외부 사이트는 부르지 않는다 — 시간·크기 제한은 루프백 테스트 서버(테스트 전용 주소 정책)로 확인한다.
 */
class SafePageFetcherTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", ex -> {
            byte[] body = "<html><body>목차</body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/to-private", ex -> {
            ex.getResponseHeaders().add("Location", "http://10.0.0.1/admin");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/to-metadata", ex -> {
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
            ex.sendResponseHeaders(301, -1);
            ex.close();
        });
        server.createContext("/loop", ex -> {
            ex.getResponseHeaders().add("Location", "/loop");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/drip", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    out.write("<p>a</p>".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(200);
                }
            } catch (Exception ignored) {
                // 클라이언트가 끊으면 쓰기가 실패한다 — 그게 기대한 동작이다.
            }
        });
        server.createContext("/big", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                byte[] chunk = new byte[64 * 1024];
                for (int i = 0; i < 20; i++) {
                    out.write(chunk);
                }
            } catch (Exception ignored) {
                // 끊김
            }
        });
        server.createContext("/gzip-bomb", ex -> {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(compressed)) {
                gz.write(new byte[2 * 1024 * 1024]);
            }
            byte[] body = compressed.toByteArray();
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/pdf", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/pdf");
            ex.sendResponseHeaders(200, 4);
            try (OutputStream out = ex.getResponseBody()) {
                out.write("%PDF".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** 루프백 테스트 서버에는 닿되, 나머지 규칙(포트 검사 제외)은 그대로인 수집기. */
    private SafePageFetcher testFetcher(long deadlineMs, long maxBytes) {
        return new SafePageFetcher(2000, 2000, deadlineMs, maxBytes, SafePageFetcher.AddressPolicy.ALLOW_LOOPBACK_FOR_TEST);
    }

    private String local(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    void 공인_주소_판정() throws Exception {
        assertThat(SafePageFetcher.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(SafePageFetcher.isPublic(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
        for (String blocked : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "224.0.0.1", "198.18.0.1", "::1", "fe80::1", "fd00::1", "::ffff:127.0.0.1",
                "::ffff:10.0.0.1", "64:ff9b::a00:1", "2001:db8::1"}) {
            assertThat(SafePageFetcher.isPublic(InetAddress.getByName(blocked))).as(blocked).isFalse();
        }
    }

    @Test
    void 스킴_포트_사용자정보_내부이름은_주소_단계에서_거부한다() {
        assertThatThrownBy(() -> SafePageFetcher.normalize("file:///etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePageFetcher.normalize("ftp://example.com/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePageFetcher.normalize("https://user:pw@example.com/")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePageFetcher.normalize("http://example.com:8080/")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePageFetcher.normalize("http://localhost/")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePageFetcher.normalize("http://intranet/")).isInstanceOf(IllegalArgumentException.class);
        assertThat(SafePageFetcher.normalize("https://WWW.Yes24.com/product/goods/1?a=%26#frag").toString())
                .isEqualTo("https://www.yes24.com/product/goods/1?a=%26");
        assertThat(SafePageFetcher.masked("https://x.example.com/p?token=secret")).isEqualTo("https://x.example.com/p?…");
    }

    @Test
    void 운영_정책에서는_내부_IP_리터럴로_연결하지_않는다() {
        SafePageFetcher fetcher = new SafePageFetcher(1000, 1000, 3000, 1024);
        try {
            SafePageFetcher.Page page = fetcher.fetch("http://10.0.0.1/admin");
            assertThat(page.status()).isEqualTo(SafePageFetcher.Status.BLOCKED_ADDRESS);
            SafePageFetcher.Page meta = fetcher.fetch("http://169.254.169.254/latest/meta-data");
            assertThat(meta.status()).isEqualTo(SafePageFetcher.Status.BLOCKED_ADDRESS);
        } finally {
            fetcher.destroy();
        }
    }

    @Test
    void 정상_HTML을_받고_리다이렉트는_매번_다시_검사한다() {
        SafePageFetcher fetcher = testFetcher(5000, 1024 * 1024);
        try {
            SafePageFetcher.Page ok = fetcher.fetch(local("/ok"));
            assertThat(ok.status()).isEqualTo(SafePageFetcher.Status.OK);
            assertThat(ok.html()).contains("목차");

            // 테스트 정책도 사설 주소(10/8, 169.254/16)는 막는다 — 리다이렉트 뒤 주소를 다시 검사한다.
            assertThat(fetcher.fetch(local("/to-private")).status()).isEqualTo(SafePageFetcher.Status.BLOCKED_ADDRESS);
            assertThat(fetcher.fetch(local("/to-metadata")).status()).isEqualTo(SafePageFetcher.Status.BLOCKED_ADDRESS);
            assertThat(fetcher.fetch(local("/loop")).status()).isEqualTo(SafePageFetcher.Status.TOO_MANY_REDIRECTS);
            assertThat(fetcher.fetch(local("/pdf")).status()).isEqualTo(SafePageFetcher.Status.NOT_HTML);
        } finally {
            fetcher.destroy();
        }
    }

    @Test
    void 자동_수집은_허용한_사이트만_열고_리다이렉트로_다른_곳에_가도_막는다() {
        SafePageFetcher fetcher = testFetcher(5000, 1024 * 1024);
        try {
            assertThat(fetcher.fetch(local("/ok"), host -> false).status())
                    .isEqualTo(SafePageFetcher.Status.NOT_ALLOWED_HOST);
            // 처음 주소는 허용, 리다이렉트로 간 10.0.0.1은 허용 목록 밖.
            assertThat(fetcher.fetch(local("/to-private"), "127.0.0.1"::equals).status())
                    .isEqualTo(SafePageFetcher.Status.NOT_ALLOWED_HOST);
            assertThat(WebEvidenceStore.supportedHost("www.yes24.com")).isTrue();
            assertThat(WebEvidenceStore.supportedHost("yes24.com.evil.example")).isFalse();
            WebEvidenceStore store = new WebEvidenceStore(null, null);
            assertThat(store.target("https://m.yes24.com/goods/detail/175899340", 1L).url())
                    .isEqualTo("https://www.yes24.com/product/goods/175899340");
            assertThat(store.target("https://www.aladin.co.kr/m/mproduct.aspx?ItemId=237039960", 1L).cacheScopeKey())
                    .isEqualTo("SHARED");
        } finally {
            fetcher.destroy();
        }
    }

    @Test
    void 조금씩_보내는_서버는_전체_기한에_끊고_연결을_돌려준다() {
        SafePageFetcher fetcher = testFetcher(1500, 1024 * 1024);
        try {
            long started = System.currentTimeMillis();
            SafePageFetcher.Page page = fetcher.fetch(local("/drip"));
            long took = System.currentTimeMillis() - started;

            assertThat(page.status()).isEqualTo(SafePageFetcher.Status.TIMEOUT);
            assertThat(took).isLessThan(5000);
            // 끊긴 뒤에도 같은 수집기로 다음 페이지를 받는다(연결이 묶여 있지 않다).
            assertThat(fetcher.fetch(local("/ok")).status()).isEqualTo(SafePageFetcher.Status.OK);
        } finally {
            fetcher.destroy();
        }
    }

    @Test
    void 크기는_압축을_푼_뒤의_실제_바이트로_센다() {
        SafePageFetcher fetcher = testFetcher(5000, 512 * 1024);
        try {
            assertThat(fetcher.fetch(local("/big")).status()).isEqualTo(SafePageFetcher.Status.TOO_LARGE);
            assertThat(fetcher.fetch(local("/gzip-bomb")).status()).isEqualTo(SafePageFetcher.Status.TOO_LARGE);
        } finally {
            fetcher.destroy();
        }
    }
}
