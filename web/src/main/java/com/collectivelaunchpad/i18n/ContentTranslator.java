package com.collectivelaunchpad.i18n;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.collectivelaunchpad.agentclient.AgentClient;
import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Product;
import com.collectivelaunchpad.domain.Repositories.ProductRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;

/**
 * Keeps the texts of live shops (shop name, about, location, product names, descriptions, units)
 * translated into all 12 languages. Each text set is translated once and again only when it
 * changes, detected by a hash of the source texts. Runs every 30 seconds and right after edits.
 */
@Service
public class ContentTranslator {

    private static final Logger log = LoggerFactory.getLogger(ContentTranslator.class);

    private final SellerRepository sellers;
    private final ProductRepository products;
    private final TranslationService translations;
    private final ExecutorService background;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    public ContentTranslator(SellerRepository sellers, ProductRepository products, TranslationService translations,
                             ExecutorService backgroundExecutor,
                             @Value("${launchpad.translate-content:true}") boolean enabled) {
        this.sellers = sellers;
        this.products = products;
        this.translations = translations;
        this.background = backgroundExecutor;
        this.enabled = enabled;
    }

    /** Called after a shop or product is saved: translate the change without waiting for the timer. */
    public void soon() {
        if (enabled) {
            background.submit(this::sweep);
        }
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 30_000)
    void scheduled() {
        if (enabled) {
            sweep();
        }
    }

    /** Translates every live shop and product whose texts changed. Returns how many were translated. */
    public int sweep() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        int done = 0;
        try {
            for (Seller s : sellers.findByStatusOrderByShopNameAsc(SellerStatus.PUBLISHED)) {
                Map<String, String> fields = sellerFields(s);
                String hash = hash(fields);
                if (!hash.equals(s.i18nSourceHash) && translateSeller(s.id, fields, hash)) {
                    done++;
                }
                for (Product p : products.findBySellerIdAndIsAvailableTrueOrderBySortOrderAscIdAsc(s.id)) {
                    Map<String, String> pf = productFields(p);
                    String ph = hash(pf);
                    if (!ph.equals(p.i18nSourceHash) && translateProduct(p.id, pf, ph)) {
                        done++;
                    }
                }
            }
        } catch (AgentClient.AgentException e) {
            log.warn("Translating shop texts failed, will retry: {}", e.getMessage());
        } finally {
            running.set(false);
        }
        return done;
    }

    private boolean translateSeller(long id, Map<String, String> fields, String hash) {
        var result = translations.translate(fields, Languages.CODES);
        return sellers.findById(id).filter(s -> hash.equals(hash(sellerFields(s)))).map(s -> {
            s.i18n = result;
            s.i18nSourceHash = hash;
            sellers.save(s);
            return true;
        }).orElse(false); // edited meanwhile: the next sweep picks up the new text
    }

    private boolean translateProduct(long id, Map<String, String> fields, String hash) {
        var result = translations.translate(fields, Languages.CODES);
        return products.findById(id).filter(p -> hash.equals(hash(productFields(p)))).map(p -> {
            p.i18n = result;
            p.i18nSourceHash = hash;
            products.save(p);
            return true;
        }).orElse(false);
    }

    static Map<String, String> sellerFields(Seller s) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("name", s.name);
        f.put("shop_name", s.shopName);
        f.put("bio", s.bio);
        f.put("location", s.location);
        return f;
    }

    static Map<String, String> productFields(Product p) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("name", p.name);
        f.put("description", p.description);
        f.put("unit", p.unit);
        return f;
    }

    static String hash(Map<String, String> fields) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            fields.forEach((k, v) -> {
                d.update(k.getBytes(StandardCharsets.UTF_8));
                d.update((byte) 0);
                d.update((v == null ? "" : v).getBytes(StandardCharsets.UTF_8));
                d.update((byte) 1);
            });
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
