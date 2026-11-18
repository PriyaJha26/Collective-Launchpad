package com.collectivelaunchpad.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.tenant.HostClassifier;

/**
 * Caddy asks here before requesting a certificate for a hostname ("on-demand TLS").
 * 200 only for the main domain, www and published shops, so made-up subdomains never get one.
 */
@RestController
public class TlsCheckController {

    private final HostClassifier classifier;
    private final SellerRepository sellers;

    public TlsCheckController(HostClassifier classifier, SellerRepository sellers) {
        this.classifier = classifier;
        this.sellers = sellers;
    }

    @GetMapping("/internal/tls-check")
    public ResponseEntity<Void> check(@RequestParam(defaultValue = "") String domain) {
        HostClassifier.Result host = classifier.classify(domain);
        boolean allowed = switch (host.kind()) {
            case MAIN, WWW -> true;
            case SHOP -> sellers.findBySlugAndStatus(host.slug(), SellerStatus.PUBLISHED).isPresent();
            case UNKNOWN -> false;
        };
        return allowed ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}
