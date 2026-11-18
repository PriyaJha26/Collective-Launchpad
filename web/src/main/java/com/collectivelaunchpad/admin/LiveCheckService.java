package com.collectivelaunchpad.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.collectivelaunchpad.config.SiteUrls;
import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;

/**
 * After publishing, request https://<slug>.<domain>/ ourselves. In production this first request
 * makes Caddy fetch the certificate, and it confirms the page is really live.
 */
@Service
public class LiveCheckService {

    private static final Logger log = LoggerFactory.getLogger(LiveCheckService.class);

    private final SellerRepository sellers;
    private final SiteUrls urls;
    private final ExecutorService background;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public LiveCheckService(SellerRepository sellers, SiteUrls urls, ExecutorService backgroundExecutor) {
        this.sellers = sellers;
        this.urls = urls;
        this.background = backgroundExecutor;
    }

    public void checkInBackground(long sellerId) {
        background.submit(() -> check(sellerId));
    }

    public void checkAllPublishedInBackground() {
        sellers.findByStatusOrderByShopNameAsc(SellerStatus.PUBLISHED).forEach(s -> checkInBackground(s.id));
    }

    void check(long sellerId) {
        Seller s = sellers.findById(sellerId).orElse(null);
        if (s == null || s.slug == null) {
            return;
        }
        String url = urls.shopUrl(s.slug);
        boolean ok = false;
        String detail;
        long start = System.nanoTime();
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30)).GET();
            if (urls.isLocal()) {
                // The JVM does not resolve *.localhost; connect to loopback with the shop's Host header.
                req.uri(URI.create("http://127.0.0.1:" + urls.port() + "/")).header("Host", urls.shopHost(s.slug) + ":" + urls.port());
            } else {
                req.uri(URI.create(url));
            }
            HttpResponse<Void> res = http.send(req.build(), HttpResponse.BodyHandlers.discarding());
            long ms = (System.nanoTime() - start) / 1_000_000;
            ok = res.statusCode() == 200;
            String tls = res.sslSession().isPresent() ? "HTTPS OK" : (urls.isLocal() ? "local, no HTTPS" : "no HTTPS");
            detail = ok ? "Live (" + tls + ", " + ms + " ms)" : "Page returned HTTP " + res.statusCode();
        } catch (javax.net.ssl.SSLException e) {
            detail = "HTTPS certificate problem: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            detail = "Interrupted";
        } catch (Exception e) {
            detail = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        s = sellers.findById(sellerId).orElse(null);
        if (s == null) {
            return;
        }
        s.liveCheckOk = ok;
        s.liveCheckAt = OffsetDateTime.now();
        s.liveCheckDetail = detail;
        sellers.save(s);
        log.info("Live check {} -> {}", url, detail);
    }
}
