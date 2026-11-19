package com.collectivelaunchpad.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Product;
import com.collectivelaunchpad.domain.Repositories.OnboardingSessionRepository;
import com.collectivelaunchpad.domain.Repositories.ProductRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.site.SiteController;
import com.collectivelaunchpad.tenant.SlugRules;

/** Review, edit, publish and hide shops. Publishing is always an admin action. */
@Controller
public class SellerAdminController {

    /** Edit form. Public fields bound directly (see AdminModelAdvice). */
    public static class SellerForm {
        public String slug;
        public String name;
        public String shopName;
        public String bio;
        public String location;
        public String whatsapp;
        public String upiId;
        public List<ProductForm> products = new ArrayList<>();
    }

    public static class ProductForm {
        public Long id;
        public String name;
        public String description;
        public String price;
        public String unit;
        public String stock;
        public boolean available = true;
        public boolean remove;
    }

    static class FormError extends RuntimeException {
        FormError(String message) {
            super(message);
        }
    }

    private final SellerRepository sellers;
    private final ProductRepository products;
    private final OnboardingSessionRepository sessions;
    private final LiveCheckService liveCheck;
    private final SiteController site;
    private final TransactionTemplate tx;
    private final com.collectivelaunchpad.domain.Repositories.AccountRepository accounts;
    private final com.collectivelaunchpad.i18n.ContentTranslator contentTranslator;

    public SellerAdminController(SellerRepository sellers, ProductRepository products, OnboardingSessionRepository sessions,
                                 LiveCheckService liveCheck, SiteController site, TransactionTemplate tx,
                                 com.collectivelaunchpad.domain.Repositories.AccountRepository accounts,
                                 com.collectivelaunchpad.i18n.ContentTranslator contentTranslator) {
        this.contentTranslator = contentTranslator;
        this.accounts = accounts;
        this.sellers = sellers;
        this.products = products;
        this.sessions = sessions;
        this.liveCheck = liveCheck;
        this.site = site;
        this.tx = tx;
    }

    @GetMapping("/admin/sellers/{id}")
    public String edit(@PathVariable long id, Model model) {
        Seller s = seller(id);
        model.addAttribute("seller", s);
        model.addAttribute("products", products.findBySellerIdOrderBySortOrderAscIdAsc(id));
        model.addAttribute("onb", sessions.findFirstBySellerIdOrderByIdDesc(id).orElse(null));
        model.addAttribute("login", accounts.findBySellerId(id).orElse(null));
        return "admin/seller-edit";
    }

    @PostMapping("/admin/sellers/{id}")
    public String save(@PathVariable long id, @ModelAttribute("form") SellerForm form, RedirectAttributes flash) {
        seller(id);
        try {
            // All or nothing: a validation error rolls back every change in the form.
            tx.executeWithoutResult(t -> apply(seller(id), form));
            contentTranslator.soon();
            flash.addFlashAttribute("notice", "Saved. Highlighted values are now marked as checked.");
        } catch (FormError e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/sellers/" + id;
    }

    @PostMapping("/admin/sellers/{id}/publish")
    public String publish(@PathVariable long id, RedirectAttributes flash) {
        Seller s = seller(id);
        List<String> missing = new ArrayList<>();
        if (s.slug == null) {
            missing.add("a web address");
        }
        if (s.shopName == null || s.shopName.isBlank()) {
            missing.add("a shop name");
        }
        if (products.countBySellerId(id) == 0) {
            missing.add("at least one product");
        }
        if (!missing.isEmpty()) {
            flash.addFlashAttribute("error", "Cannot publish yet. Missing: " + String.join(", ", missing) + ".");
            return "redirect:/admin/sellers/" + id;
        }
        s.status = SellerStatus.PUBLISHED;
        s.verifiedAt = OffsetDateTime.now();
        s.liveCheckOk = null;
        s.liveCheckDetail = "Checking…";
        sellers.save(s);
        liveCheck.checkInBackground(id);
        contentTranslator.soon();
        flash.addFlashAttribute("notice", "Published. Checking that " + s.slug + " is live…");
        return "redirect:/admin/sellers/" + id;
    }

    @PostMapping("/admin/sellers/{id}/hide")
    public String hide(@PathVariable long id, RedirectAttributes flash) {
        Seller s = seller(id);
        if (s.isPublished()) {
            s.status = SellerStatus.HIDDEN;
            sellers.save(s);
            flash.addFlashAttribute("notice", "Hidden. The shop page now shows \"not found\".");
        }
        return "redirect:/admin/sellers/" + id;
    }

    @PostMapping("/admin/sellers/{id}/recheck")
    public String recheck(@PathVariable long id) {
        seller(id);
        liveCheck.checkInBackground(id);
        return "redirect:/admin/sellers/" + id;
    }

    @PostMapping("/admin/sellers/check-all")
    public String checkAll(RedirectAttributes flash) {
        liveCheck.checkAllPublishedInBackground();
        flash.addFlashAttribute("notice", "Checking all live shops. Refresh in a few seconds.");
        return "redirect:/admin";
    }

    @GetMapping("/admin/sellers/{id}/preview")
    public String preview(@PathVariable long id, Model model) {
        site.shopModel(model, seller(id), true);
        return "site/shop";
    }

    // ------------------------------------------------------------------ helpers

    private Seller seller(long id) {
        return sellers.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void apply(Seller s, SellerForm f) {
        String slug = SlugRules.normalize(f.slug);
        if (!slug.isEmpty() || s.slug != null) {
            SlugRules.problem(slug).ifPresent(p -> {
                throw new FormError("Web address: " + p);
            });
            if (sellers.existsBySlugAndIdNot(slug, s.id)) {
                throw new FormError("The web address \"" + slug + "\" is taken by another shop.");
            }
            s.slug = slug;
        }
        s.name = required(f.name, "Seller's name", 80);
        s.shopName = required(f.shopName, "Shop name", 80);
        s.bio = optional(f.bio, "About", 1200);
        s.location = optional(f.location, "Location", 120);
        s.whatsapp = optional(f.whatsapp, "WhatsApp", 20);
        s.upiId = optional(f.upiId, "UPI ID", 100);
        s.reviewFields = "";
        sellers.save(s);

        Map<Long, Product> existing = products.findBySellerIdOrderBySortOrderAscIdAsc(s.id).stream()
                .collect(Collectors.toMap(p -> p.id, Function.identity()));
        int order = 0;
        for (ProductForm pf : f.products) {
            if (pf == null) {
                continue;
            }
            Product p = pf.id == null ? null : existing.get(pf.id);
            if (pf.id != null && p == null) {
                continue; // not this seller's product
            }
            boolean blank = (pf.name == null || pf.name.isBlank());
            if (pf.remove || (p == null && blank)) {
                if (p != null) {
                    products.delete(p);
                }
                continue;
            }
            if (p == null) {
                p = new Product();
                p.sellerId = s.id;
            }
            p.name = required(pf.name, "Product name", 120);
            p.description = orEmpty(optional(pf.description, "Description", 600));
            p.pricePaise = price(pf.price, p.name);
            p.unit = orEmpty(optional(pf.unit, "Unit", 40));
            p.stock = stock(pf.stock, p.name);
            p.isAvailable = pf.available;
            p.needsReview = false;
            p.reviewFields = "";
            p.sortOrder = order++;
            products.save(p);
        }
    }

    private static Long price(String raw, String product) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal rupees = new BigDecimal(raw.strip().replace(",", "").replace("₹", ""));
            if (rupees.signum() < 0 || rupees.compareTo(BigDecimal.valueOf(10_000_000)) > 0) {
                throw new NumberFormatException();
            }
            return rupees.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new FormError("Price for \"" + product + "\" must be a number of rupees, like 450 or 12.50.");
        }
    }

    private static Integer stock(String raw, String product) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int n = Integer.parseInt(raw.strip());
            if (n < 0 || n > 100_000) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new FormError("Stock for \"" + product + "\" must be a whole number, or empty for made to order.");
        }
    }

    private static String required(String v, String label, int max) {
        String t = optional(v, label, max);
        if (t == null) {
            throw new FormError(label + " is required.");
        }
        return t;
    }

    private static String optional(String v, String label, int max) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String t = v.strip();
        if (t.length() > max) {
            throw new FormError(label + " is too long (maximum " + max + " characters).");
        }
        return t;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
