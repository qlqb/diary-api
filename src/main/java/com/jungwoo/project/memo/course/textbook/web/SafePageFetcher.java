package com.jungwoo.project.memo.course.textbook.web;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 외부 웹 페이지 한 장을 안전하게 받는다. 교재 상세 페이지(서점·출판사)와 사용자가 준 링크가 같은 길을 쓴다.
 *
 * <p>링크는 모두 외부 입력이다 — 모델이 낸 URL도, 사용자가 붙인 URL도. 서버 안쪽을 찌르는 통로가 되지 않게:
 * <ul>
 *   <li>http/https, 포트 80/443만. 사용자 정보(user:pass@)가 붙은 URL은 거부한다.</li>
 *   <li>이름 해석은 {@link GuardedDnsResolver}가 한다. 해석한 주소가 <b>하나라도</b> 공인 주소가 아니면 실패시키고,
 *       통과한 주소만 연결기에 돌려준다 — 검사한 주소와 실제로 연결하는 주소가 같다(검사 뒤 재해석으로 바꿔치기 불가).
 *       TLS 인증서 검증과 SNI는 원래 호스트 이름으로 한다.</li>
 *   <li>자동 리다이렉트는 끈다. 최대 {@link #MAX_REDIRECTS}번 직접 따라가며 매번 같은 검사를 다시 한다.</li>
 *   <li>쿠키·인증 헤더를 보내지 않고 받은 쿠키도 저장하지 않는다. 자동 재시도도 끈다.</li>
 *   <li>시간: 연결·응답 타임아웃과 별도로 <b>페이지당 전체 기한</b>(이름 해석·연결 풀 대기·TLS·리다이렉트·본문 읽기
 *       모두 포함)이 지나면 요청을 취소한다. 조금씩 계속 보내는 서버도 기한에 끊긴다.</li>
 *   <li>크기: 압축을 푼 뒤 실제로 읽은 바이트로 센다(Content-Length를 믿지 않는다).</li>
 *   <li>HTML만 받는다.</li>
 * </ul>
 * 로그에는 쿼리를 뺀 주소만 남긴다({@link #masked}).
 */
@Slf4j
@Component
public class SafePageFetcher implements DisposableBean {

    static final int MAX_REDIRECTS = 3;
    static final String USER_AGENT = "Mozilla/5.0 (compatible; diary-textbook-lookup/1.0)";

    private final long pageDeadlineMillis;
    private final long maxBytes;
    private final CloseableHttpClient client;
    private final PoolingHttpClientConnectionManager connections;
    private final ScheduledExecutorService canceller;
    private final AddressPolicy addressPolicy;

    @org.springframework.beans.factory.annotation.Autowired
    public SafePageFetcher(@Value("${textbook.fetch.connect-timeout-ms:5000}") long connectTimeoutMs,
                           @Value("${textbook.fetch.response-timeout-ms:10000}") long responseTimeoutMs,
                           @Value("${textbook.fetch.page-deadline-ms:15000}") long pageDeadlineMs,
                           @Value("${textbook.fetch.max-bytes:3145728}") long maxBytes) {
        this(connectTimeoutMs, responseTimeoutMs, pageDeadlineMs, maxBytes, AddressPolicy.PUBLIC_ONLY);
    }

    /** 테스트용: 주소 정책을 바꿔 로컬 테스트 서버로 시간·크기 제한을 검증한다. */
    SafePageFetcher(long connectTimeoutMs, long responseTimeoutMs, long pageDeadlineMs, long maxBytes,
                    AddressPolicy addressPolicy) {
        this.pageDeadlineMillis = pageDeadlineMs;
        this.maxBytes = maxBytes;
        this.addressPolicy = addressPolicy;
        this.connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new GuardedDnsResolver(addressPolicy))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                        .setSocketTimeout(Timeout.ofMilliseconds(responseTimeoutMs))
                        .build())
                .setMaxConnTotal(8)
                .setMaxConnPerRoute(2)
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .setUserAgent(USER_AGENT)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                        .setResponseTimeout(Timeout.ofMilliseconds(responseTimeoutMs))
                        .build())
                .build();
        this.canceller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "textbook-fetch-deadline");
            t.setDaemon(true);
            return t;
        });
    }

    /** 받은 결과. status가 OK가 아니면 html은 null이다. */
    public record Page(Status status, String requestedUrl, String finalUrl, int httpStatus, String html,
                       String detail) {
    }

    public enum Status {
        OK,
        /** 주소 자체를 받지 않는다(형식·스킴·포트·사용자 정보). */
        INVALID_URL,
        /** 이름이 내부·비공인 주소로 해석된다. */
        BLOCKED_ADDRESS,
        /** 200이 아니다(404·403 등). */
        HTTP_ERROR,
        /** HTML이 아니다. */
        NOT_HTML,
        TOO_LARGE,
        TIMEOUT,
        TOO_MANY_REDIRECTS,
        /** 연결·TLS·이름 해석 실패. */
        NETWORK_ERROR,
        /** 이 경로에서 허용하지 않는 사이트(자동 수집은 지원 서점만 — 리다이렉트로 다른 곳에 가도 막는다). */
        NOT_ALLOWED_HOST
    }

    public Page fetch(String rawUrl) {
        return fetch(rawUrl, host -> true);
    }

    /**
     * @param hostAllowed 처음 주소와 리다이렉트로 가는 모든 주소의 호스트가 통과해야 하는 조건
     */
    public Page fetch(String rawUrl, java.util.function.Predicate<String> hostAllowed) {
        URI uri;
        try {
            uri = normalize(rawUrl, addressPolicy);
        } catch (IllegalArgumentException e) {
            return new Page(Status.INVALID_URL, rawUrl, null, 0, null, e.getMessage());
        }
        long deadline = System.currentTimeMillis() + pageDeadlineMillis;
        String requested = uri.toString();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!hostAllowed.test(uri.getHost())) {
                return new Page(Status.NOT_ALLOWED_HOST, requested, masked(uri.toString()), 0, null, "허용하지 않는 사이트");
            }
            if (System.currentTimeMillis() >= deadline) {
                return new Page(Status.TIMEOUT, requested, uri.toString(), 0, null, "전체 기한 초과");
            }
            HttpGet get = new HttpGet(uri);
            get.setHeader("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1");
            get.setHeader("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.6");
            ScheduledFuture<?> cancel = canceller.schedule(get::cancel,
                    Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            try (CloseableHttpResponse response = client.execute(get)) {
                int code = response.getCode();
                if (code >= 300 && code < 400) {
                    Header location = response.getFirstHeader("Location");
                    if (location == null) {
                        return new Page(Status.HTTP_ERROR, requested, uri.toString(), code, null, "Location 없음");
                    }
                    try {
                        uri = normalize(uri.resolve(location.getValue().trim()).toString(), addressPolicy);
                    } catch (IllegalArgumentException e) {
                        return new Page(Status.INVALID_URL, requested, masked(uri.toString()), code, null,
                                "리다이렉트 주소 거부: " + e.getMessage());
                    }
                    continue;
                }
                if (code != 200) {
                    return new Page(Status.HTTP_ERROR, requested, uri.toString(), code, null, "HTTP " + code);
                }
                HttpEntity entity = response.getEntity();
                if (entity == null || entity.getContentLength() == 0) {
                    // 200인데 본문이 비었다 — CDN이 자동 접속을 막을 때 이렇게 준다(교보문고, 2026-10-04).
                    return new Page(Status.HTTP_ERROR, requested, uri.toString(), code, null, "본문 없음");
                }
                ContentType type = ContentType.parseLenient(entity.getContentType());
                String mime = type == null ? "" : type.getMimeType().toLowerCase(Locale.ROOT);
                if (!(mime.equals("text/html") || mime.equals("application/xhtml+xml"))) {
                    return new Page(Status.NOT_HTML, requested, uri.toString(), code, null, mime);
                }
                byte[] body;
                try (InputStream in = entity.getContent()) {
                    body = readLimited(in, deadline);
                } catch (TooLarge e) {
                    return new Page(Status.TOO_LARGE, requested, uri.toString(), code, null, "본문 " + maxBytes + "바이트 초과");
                } catch (DeadlinePassed e) {
                    return new Page(Status.TIMEOUT, requested, uri.toString(), code, null, "본문 읽기 기한 초과");
                }
                Charset charset = type.getCharset();
                String html = decode(body, charset);
                return new Page(Status.OK, requested, uri.toString(), code, html, null);
            } catch (BlockedAddressException e) {
                return new Page(Status.BLOCKED_ADDRESS, requested, uri.toString(), 0, null, e.getMessage());
            } catch (IOException e) {
                if (System.currentTimeMillis() >= deadline || get.isCancelled()) {
                    return new Page(Status.TIMEOUT, requested, uri.toString(), 0, null, "전체 기한 초과");
                }
                if (e.getCause() instanceof BlockedAddressException blocked) {
                    return new Page(Status.BLOCKED_ADDRESS, requested, uri.toString(), 0, null, blocked.getMessage());
                }
                return new Page(Status.NETWORK_ERROR, requested, uri.toString(), 0, null, e.getClass().getSimpleName());
            } finally {
                cancel.cancel(false);
            }
        }
        return new Page(Status.TOO_MANY_REDIRECTS, requested, uri.toString(), 0, null, "리다이렉트 " + MAX_REDIRECTS + "회 초과");
    }

    private byte[] readLimited(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new TooLarge();
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new DeadlinePassed();
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 헤더에 문자셋이 없으면 HTML의 meta charset을 보고, 그것도 없으면 UTF-8. */
    static String decode(byte[] body, Charset fromHeader) {
        if (fromHeader != null) {
            return new String(body, fromHeader);
        }
        String head = new String(body, 0, Math.min(body.length, 4096), java.nio.charset.StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("charset\\s*=\\s*[\"']?([a-z0-9_\\-]+)").matcher(head);
        if (m.find()) {
            try {
                return new String(body, Charset.forName(m.group(1)));
            } catch (Exception ignored) {
                // 모르는 문자셋 이름은 UTF-8로 읽는다.
            }
        }
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ===== 주소 검사 =====

    /**
     * 받을 수 있는 주소인가. 스킴·포트·사용자 정보·호스트 모양만 본다(이름 해석은 연결할 때 {@link GuardedDnsResolver}).
     * 조각(#…)은 버린다. 호스트는 IDN을 ASCII로 바꾸고 소문자로.
     */
    public static URI normalize(String raw) {
        return normalize(raw, AddressPolicy.PUBLIC_ONLY);
    }

    static URI normalize(String raw, AddressPolicy policy) {
        boolean loopbackTest = policy == AddressPolicy.ALLOW_LOOPBACK_FOR_TEST;
        if (raw == null || raw.isBlank() || raw.length() > 2000) {
            throw new IllegalArgumentException("빈 주소이거나 너무 길다");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("주소 형식이 아니다");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("http/https만 받는다");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("사용자 정보가 붙은 주소는 받지 않는다");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("호스트가 없다");
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443 && !(loopbackTest && "127.0.0.1".equals(host))) {
            throw new IllegalArgumentException("포트 80/443만 받는다");
        }
        String asciiHost;
        try {
            asciiHost = (host.startsWith("[") ? host : IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalArgumentException("호스트 이름이 올바르지 않다");
        }
        if (asciiHost.endsWith(".")) {
            asciiHost = asciiHost.substring(0, asciiHost.length() - 1);
        }
        if (asciiHost.equals("localhost") || asciiHost.endsWith(".localhost") || asciiHost.endsWith(".local")
                || asciiHost.endsWith(".internal") || !asciiHost.contains(".") && !asciiHost.startsWith("[")) {
            throw new IllegalArgumentException("내부 이름은 받지 않는다");
        }
        String rawPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String rawQuery = uri.getRawQuery();
        try {
            // 원래 인코딩을 그대로 둔다(디코딩 뒤 다시 인코딩하면 %26 같은 값이 바뀐다).
            return new URI(scheme + "://" + asciiHost + (port == -1 ? "" : ":" + port) + rawPath
                    + (rawQuery == null ? "" : "?" + rawQuery));
        } catch (Exception e) {
            throw new IllegalArgumentException("주소를 정규화하지 못했다");
        }
    }

    /** 로그·응답용: 쿼리·조각을 뺀 주소. */
    public static String masked(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI u = new URI(url);
            return (u.getScheme() == null ? "" : u.getScheme() + "://") + (u.getHost() == null ? "" : u.getHost())
                    + (u.getRawPath() == null ? "" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?…");
        } catch (Exception e) {
            int q = url.indexOf('?');
            return q < 0 ? url : url.substring(0, q) + "?…";
        }
    }

    /** 어떤 주소에 연결해도 되는가. */
    public enum AddressPolicy {
        PUBLIC_ONLY,
        /** 테스트 전용: 루프백 테스트 서버를 허용한다. */
        ALLOW_LOOPBACK_FOR_TEST
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(b);
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false; // fc00::/7 고유 로컬
            }
            if (first == 0xfe && (second & 0xc0) == 0x80) {
                return false; // fe80::/10 링크 로컬
            }
            if (first == 0xfe && (second & 0xc0) == 0xc0) {
                return false; // fec0::/10 옛 사이트 로컬
            }
            if (first == 0x20 && second == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return false; // 2001:db8::/32 문서용
            }
            boolean zeroPrefix = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    zeroPrefix = false;
                    break;
                }
            }
            if (zeroPrefix && (((b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) || (b[10] == 0 && b[11] == 0))) {
                // ::ffff:a.b.c.d(IPv4 대응), ::a.b.c.d(옛 호환) — 안의 IPv4로 판정
                return isPublicV4(new byte[]{b[12], b[13], b[14], b[15]});
            }
            if ((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
                return false; // 64:ff9b::/96 NAT64 — 안쪽 IPv4로 나갈 수 있다
            }
            if (first == 0x20 && second == 0x02) {
                return false; // 2002::/16 6to4
            }
            return (first & 0xe0) == 0x20; // 전역 유니캐스트 2000::/3만
        }
        return false;
    }

    private static boolean isPublicV4(byte[] b) {
        int a0 = b[0] & 0xff;
        int a1 = b[1] & 0xff;
        int a2 = b[2] & 0xff;
        if (a0 == 0 || a0 == 10 || a0 == 127 || a0 >= 224) {
            return false; // 0/8, 10/8, 127/8, 멀티캐스트·예약·브로드캐스트
        }
        if (a0 == 100 && a1 >= 64 && a1 <= 127) {
            return false; // 100.64/10 CGNAT
        }
        if (a0 == 169 && a1 == 254) {
            return false; // 링크 로컬·클라우드 메타데이터
        }
        if (a0 == 172 && a1 >= 16 && a1 <= 31) {
            return false;
        }
        if (a0 == 192 && a1 == 168) {
            return false;
        }
        if (a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)) {
            return false; // 192.0.0/24, 192.0.2/24
        }
        if (a0 == 198 && (a1 == 18 || a1 == 19)) {
            return false; // 198.18/15 벤치마크
        }
        if (a0 == 198 && a1 == 51 && a2 == 100) {
            return false;
        }
        if (a0 == 203 && a1 == 0 && a2 == 113) {
            return false;
        }
        return true;
    }

    /** 해석한 주소를 전부 검사하고, 통과한 주소만 연결기에 넘긴다. */
    static final class GuardedDnsResolver implements DnsResolver {
        private final AddressPolicy policy;

        GuardedDnsResolver(AddressPolicy policy) {
            this.policy = policy;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] all = InetAddress.getAllByName(host);
            if (all.length == 0) {
                throw new UnknownHostException(host);
            }
            for (InetAddress address : all) {
                boolean ok = isPublic(address)
                        || policy == AddressPolicy.ALLOW_LOOPBACK_FOR_TEST && address.isLoopbackAddress();
                if (!ok) {
                    throw new BlockedAddressException("내부·비공인 주소로 해석되는 이름: " + host);
                }
            }
            return all;
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }

    static final class BlockedAddressException extends UnknownHostException {
        BlockedAddressException(String message) {
            super(message);
        }
    }

    private static final class TooLarge extends IOException {
    }

    private static final class DeadlinePassed extends IOException {
    }

    @Override
    public void destroy() {
        canceller.shutdownNow();
        try {
            client.close();
        } catch (IOException ignored) {
            // 종료 중
        }
        connections.close();
    }
}
