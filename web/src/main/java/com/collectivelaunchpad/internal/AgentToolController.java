package com.collectivelaunchpad.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.collectivelaunchpad.config.SiteUrls;
import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Product;
import com.collectivelaunchpad.domain.Repositories.ProductRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.tenant.SlugRules;
import com.collectivelaunchpad.uploads.UploadService;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The onboarding agent's tools land here. The seller id in the path is set by the Python service
 * from the Java request that started the turn, never by the model. Only drafts can be changed.
 * Error messages are written for Claude to read and act on.
 */
@RestController
@RequestMapping("/internal/agent/sellers/{sellerId}")
@Transactional
public class AgentToolController {

    static final int MAX_PRODUCTS = 60;
    private static final Set<String> PROFILE_FIELDS = Set.of("shop_name", "bio", "location");
    private static final Set<String> PRODUCT_FIELDS = Set.of("name", "description", "price", "unit", "photo");

    /** Becomes a 4xx response with {"error": message, ...extra}. */
    static class ToolError extends RuntimeException {
        final HttpStatus status;
        final Map<String, Object> extra;

        ToolError(HttpStatus status, String message, Map<String, Object> extra) {
            super(message);
            this.status = status;
            this.extra = extra;
        }

        ToolError(String message) {
            this(HttpStatus.BAD_REQUEST, message, Map.of());
        }
    }

    private final SellerRepository sellers;
    private final ProductRepository products;
    private final UploadService uploads;
    private final SiteUrls urls;

    public AgentToolController(SellerRepository sellers, ProductRepository products, UploadService uploads, SiteUrls urls) {
        this.sellers = sellers;
        this.products = products;
        this.uploads = uploads;
        this.urls = urls;
    }

    @PostMapping("/profile")
    public Map<String, Object> profile(@PathVariable long sellerId, @RequestBody JsonNode in) {
        Seller s = editable(sellerId);
        s.shopName = required(in, "shop_name", 80);
        s.bio = required(in, "bio", 1200);
        s.location = optional(in, "location", 120);
        s.reviewFields = String.join(",", fields(in.get("unsure_fields"), PROFILE_FIELDS));
        sellers.save(s);
        return Map.of("ok", true);
    }

    @PostMapping("/products")
    public Map<String, Object> addProduct(@PathVariable long sellerId, @RequestBody JsonNode in) {
        Seller s = editable(sellerId);
        long count = products.countBySellerId(s.id);
        if (count >= MAX_PRODUCTS) {
            throw new ToolError("This shop already has " + MAX_PRODUCTS + " products, the maximum.");
        }
        Product p = new Product();
        p.sellerId = s.id;
        p.name = required(in, "name", 120);
        p.description = orEmpty(optional(in, "description", 600));
        p.pricePaise = paise(in.get("price_rupees"));
        p.unit = orEmpty(optional(in, "unit", 40));
        p.imagePath = photo(s.id, optional(in, "photo_id", 80));
        p.sortOrder = (int) count;
        setReview(p, fields(in.get("unsure_fields"), PRODUCT_FIELDS));
        products.save(p);
        return Map.of("ok", true, "product_id", p.id);
    }

    @PatchMapping("/products/{productId}")
    public Map<String, Object> updateProduct(@PathVariable long sellerId, @PathVariable long productId, @RequestBody JsonNode in) {
        Seller s = editable(sellerId);
        Product p = products.findByIdAndSellerId(productId, s.id)
                .orElseThrow(() -> new ToolError(HttpStatus.NOT_FOUND, "No product " + productId + " in this shop.", Map.of()));
        if (present(in, "name")) {
            p.name = required(in, "name", 120);
        }
        if (present(in, "description")) {
            p.description = orEmpty(optional(in, "description", 600));
        }
        if (present(in, "price_rupees")) {
            p.pricePaise = paise(in.get("price_rupees"));
        }
        if (present(in, "unit")) {
            p.unit = orEmpty(optional(in, "unit", 40));
        }
        if (present(in, "photo_id")) {
            p.imagePath = photo(s.id, optional(in, "photo_id", 80));
        }
        if (present(in, "is_available")) {
            p.isAvailable = in.get("is_available").asBoolean(true);
        }
        if (present(in, "unsure_fields")) {
            setReview(p, fields(in.get("unsure_fields"), PRODUCT_FIELDS));
        } else if (p.pricePaise == null && !p.unsure("price")) {
            Set<String> f = new LinkedHashSet<>(p.reviewFieldSet());
            f.add("price");
            setReview(p, new ArrayList<>(f));
        }
        products.save(p);
        return Map.of("ok", true, "product_id", p.id);
    }

    @PostMapping("/address")
    public Map<String, Object> address(@PathVariable long sellerId, @RequestBody JsonNode in) {
        Seller s = editable(sellerId);
        String slug = SlugRules.normalize(text(in, "slug"));
        var problem = SlugRules.problem(slug);
        if (problem.isPresent()) {
            throw new ToolError(HttpStatus.UNPROCESSABLE_ENTITY, problem.get(), Map.of("alternatives", alternatives(slug, s)));
        }
        if (sellers.existsBySlugAndIdNot(slug, s.id)) {
            throw new ToolError(HttpStatus.CONFLICT, "The address \"" + slug + "\" is taken by another shop.",
                    Map.of("alternatives", alternatives(slug, s)));
        }
        s.slug = slug;
        sellers.save(s);
        return Map.of("ok", true, "slug", slug, "address", urls.shopHost(slug));
    }

    @PostMapping("/submit")
    public Map<String, Object> submit(@PathVariable long sellerId, @RequestBody(required = false) JsonNode in) {
        Seller s = editable(sellerId);
        List<String> missing = new ArrayList<>();
        if (s.shopName == null || s.shopName.isBlank()) {
            missing.add("a shop profile (set_shop_profile)");
        }
        if (s.slug == null) {
            missing.add("a shop address (propose_shop_address)");
        }
        if (products.countBySellerId(s.id) == 0) {
            missing.add("at least one product (add_product)");
        }
        if (!missing.isEmpty()) {
            throw new ToolError("Cannot submit yet. Missing: " + String.join(", ", missing) + ".");
        }
        s.status = SellerStatus.PENDING_REVIEW;
        sellers.save(s);
        return Map.of("ok", true, "status", s.status, "message", "Draft handed to the group admin for review.");
    }

    @ExceptionHandler(ToolError.class)
    public ResponseEntity<Map<String, Object>> handle(ToolError e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.putAll(e.extra);
        return ResponseEntity.status(e.status).body(body);
    }

    // ------------------------------------------------------------------ helpers

    private Seller editable(long sellerId) {
        Seller s = sellers.findById(sellerId)
                .orElseThrow(() -> new ToolError(HttpStatus.NOT_FOUND, "Seller not found.", Map.of()));
        if (!s.isEditableByAgent()) {
            throw new ToolError(HttpStatus.CONFLICT, "This shop is already live. Only the group admin can change it now.", Map.of());
        }
        return s;
    }

    private String photo(long sellerId, String photoId) {
        if (photoId == null) {
            return null;
        }
        return uploads.find(sellerId, photoId).map(UploadService.Photo::path)
                .orElseThrow(() -> new ToolError("Unknown photo id \"" + photoId + "\". Use one of: "
                        + String.join(", ", uploads.photoIds(sellerId)) + ", or null."));
    }

    private List<String> alternatives(String wanted, Seller s) {
        List<String> bases = new ArrayList<>();
        String cleaned = wanted.replaceAll("[^a-z0-9-]", "").replaceAll("^[^a-z]+", "").replaceAll("-+$", "");
        if (!cleaned.isEmpty()) {
            bases.add(cleaned.length() > 26 ? cleaned.substring(0, 26).replaceAll("-+$", "") : cleaned);
        }
        String fromName = s.name == null ? "" : SlugRules.normalize(s.name).replaceAll("[^a-z0-9-]", "");
        if (!fromName.isEmpty() && Character.isLetter(fromName.charAt(0))) {
            bases.add(fromName.length() > 26 ? fromName.substring(0, 26).replaceAll("-+$", "") : fromName);
        }
        if (bases.isEmpty()) {
            bases.add("shop");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String base : bases) {
            for (String candidate : List.of(base, base + "-shop", base + "2", base + "3", base + "-" + s.id)) {
                if (out.size() >= 3) {
                    break;
                }
                if (SlugRules.isValid(candidate) && !sellers.existsBySlugAndIdNot(candidate, s.id)) {
                    out.add(candidate);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static void setReview(Product p, List<String> unsure) {
        Set<String> f = new LinkedHashSet<>(unsure);
        if (p.pricePaise == null) {
            f.add("price"); // no price is always worth a look
        }
        p.reviewFields = String.join(",", f);
        p.needsReview = !f.isEmpty();
    }

    private static List<String> fields(JsonNode node, Set<String> allowed) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                if (allowed.contains(n.asText()) && !out.contains(n.asText())) {
                    out.add(n.asText());
                }
            });
        }
        return out;
    }

    static Long paise(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new ToolError("price_rupees must be a number or null.");
        }
        BigDecimal rupees = node.decimalValue();
        if (rupees.signum() < 0 || rupees.compareTo(BigDecimal.valueOf(10_000_000)) > 0) {
            throw new ToolError("price_rupees must be between 0 and 1,00,00,000.");
        }
        return rupees.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static boolean present(JsonNode in, String field) {
        return in.has(field) && !in.get(field).isNull();
    }

    private static String text(JsonNode in, String field) {
        JsonNode n = in.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String required(JsonNode in, String field, int max) {
        String v = optional(in, field, max);
        if (v == null || v.isEmpty()) {
            throw new ToolError(field + " is required.");
        }
        return v;
    }

    private static String optional(JsonNode in, String field, int max) {
        String v = text(in, field);
        if (v == null) {
            return null;
        }
        v = v.strip();
        if (v.length() > max) {
            throw new ToolError(field + " is too long (" + v.length() + " characters, maximum " + max + ").");
        }
        return v;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
