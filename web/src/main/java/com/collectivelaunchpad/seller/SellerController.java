package com.collectivelaunchpad.seller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.collectivelaunchpad.account.AppUser;
import com.collectivelaunchpad.admin.OnboardingController;
import com.collectivelaunchpad.domain.Entities.EnquiryStatus;
import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Enquiry;
import com.collectivelaunchpad.domain.Money;
import com.collectivelaunchpad.domain.OnboardingSession;
import com.collectivelaunchpad.domain.Order;
import com.collectivelaunchpad.domain.Product;
import com.collectivelaunchpad.domain.Repositories.EnquiryRepository;
import com.collectivelaunchpad.domain.Repositories.GroupRepository;
import com.collectivelaunchpad.domain.Repositories.OnboardingSessionRepository;
import com.collectivelaunchpad.domain.Repositories.OrderRepository;
import com.collectivelaunchpad.domain.Repositories.ProductRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.enquiry.EnquiryService;
import com.collectivelaunchpad.i18n.ContentTranslator;
import com.collectivelaunchpad.i18n.LanguageResolver;
import com.collectivelaunchpad.i18n.Msg;
import com.collectivelaunchpad.onboarding.OnboardingService;
import com.collectivelaunchpad.shop.OrderService;
import com.collectivelaunchpad.tenant.SlugRules;
import com.collectivelaunchpad.uploads.UploadService;

/**
 * The seller's own dashboard: their shop, products, orders, messages and earnings.
 * Every action is scoped to the seller linked to the logged-in account.
 */
@Controller
public class SellerController {

    static class FormError extends RuntimeException {
        FormError(String message) {
            super(message);
        }
    }

    private final SellerRepository sellers;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final EnquiryRepository enquiryRepo;
    private final OnboardingSessionRepository sessions;
    private final GroupRepository groups;
    private final OrderService orderService;
    private final EnquiryService enquiries;
    private final OnboardingService onboarding;
    private final UploadService uploads;
    private final TransactionTemplate tx;
    private final Msg msg;
    private final ContentTranslator contentTranslator;
    private final LanguageResolver languages;

    public SellerController(SellerRepository sellers, ProductRepository products, OrderRepository orders,
                            EnquiryRepository enquiryRepo, OnboardingSessionRepository sessions, GroupRepository groups,
                            OrderService orderService, EnquiryService enquiries, OnboardingService onboarding,
                            UploadService uploads, TransactionTemplate tx, Msg msg, ContentTranslator contentTranslator,
                            LanguageResolver languages) {
        this.msg = msg;
        this.contentTranslator = contentTranslator;
        this.languages = languages;
        this.sellers = sellers;
        this.products = products;
        this.orders = orders;
        this.enquiryRepo = enquiryRepo;
        this.sessions = sessions;
        this.groups = groups;
        this.orderService = orderService;
        this.enquiries = enquiries;
        this.onboarding = onboarding;
        this.uploads = uploads;
        this.tx = tx;
    }

    private Seller seller(AppUser me) {
        return sellers.findById(me.sellerId()).orElseThrow();
    }

    // ------------------------------------------------------------------ dashboard

    @GetMapping({"/seller", "/seller/"})
    public String dashboard(@AuthenticationPrincipal AppUser me, Model model) {
        Seller s = seller(me);
        List<Order> mine = orders.findBySellerIdOrderByCreatedAtDesc(s.id);
        model.addAttribute("seller", s);
        model.addAttribute("earnings", orderService.earnings(s.id));
        model.addAttribute("activeOrders", mine.stream().filter(Order::isActive).toList());
        model.addAttribute("newOrders", mine.stream().filter(o -> Order.PLACED.equals(o.status)).count());
        model.addAttribute("openMessages", enquiryRepo.countBySellerIdAndStatusIn(s.id, EnquiryService.OPEN));
        model.addAttribute("productCount", products.countBySellerId(s.id));
        model.addAttribute("onb", sessions.findFirstBySellerIdOrderByIdDesc(s.id).orElse(null));
        return "seller/dashboard";
    }

    // ------------------------------------------------------------------ shop settings

    @GetMapping("/seller/shop")
    public String shop(@AuthenticationPrincipal AppUser me, Model model) {
        model.addAttribute("seller", seller(me));
        model.addAttribute("languages", com.collectivelaunchpad.i18n.Languages.NATIVE);
        return "seller/shop";
    }

    @PostMapping("/seller/shop")
    public String saveShop(@AuthenticationPrincipal AppUser me, RedirectAttributes flash,
                           HttpServletRequest req, HttpServletResponse res,
                           @RequestParam(defaultValue = "") String shopName, @RequestParam(defaultValue = "") String bio,
                           @RequestParam(defaultValue = "") String location, @RequestParam(defaultValue = "") String whatsapp,
                           @RequestParam(defaultValue = "") String upiId, @RequestParam(defaultValue = "") String language,
                           @RequestParam(defaultValue = "") String slug) {
        try {
            tx.executeWithoutResult(t -> {
                Seller s = seller(me);
                s.shopName = required(shopName, "seller.shop.name", 80);
                s.bio = optional(bio, "seller.shop.bio", 1200);
                s.location = optional(location, "f.location", 120);
                s.whatsapp = optional(whatsapp, "seller.shop.whatsapp", 20);
                String upi = optional(upiId, "seller.shop.upi", 100);
                if (upi != null && !upi.matches("[A-Za-z0-9._-]{2,64}@[A-Za-z0-9.-]{2,64}")) {
                    throw new FormError(msg.get("err.upi"));
                }
                s.upiId = upi;
                if (OnboardingService.LANGUAGES.containsKey(language)) {
                    s.language = language;
                }
                // The web address is fixed once the shop is live, so links keep working.
                if (!s.isPublished() && !slug.isBlank()) {
                    String sl = SlugRules.normalize(slug);
                    SlugRules.problem(sl).ifPresent(p -> {
                        throw new FormError(msg.get("err.slugInvalid"));
                    });
                    if (sellers.existsBySlugAndIdNot(sl, s.id)) {
                        throw new FormError(msg.get("err.slugTaken", sl));
                    }
                    s.slug = sl;
                }
                sellers.save(s);
            });
            if (OnboardingService.LANGUAGES.containsKey(language)) {
                languages.setLocale(req, res, java.util.Locale.forLanguageTag(language));
            }
            contentTranslator.soon();
            flash.addFlashAttribute("notice", msg.get("common.saved"));
        } catch (FormError e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/seller/shop";
    }

    /** Self-registered shops are hidden until the superadmin approves them. */
    @PostMapping("/seller/shop/submit")
    public String submit(@AuthenticationPrincipal AppUser me, RedirectAttributes flash) {
        Seller s = seller(me);
        if (!SellerStatus.DRAFT.equals(s.status)) {
            return "redirect:/seller";
        }
        List<String> missing = new java.util.ArrayList<>();
        if (s.shopName == null || s.shopName.isBlank()) {
            missing.add(msg.get("seller.missing.name"));
        }
        if (s.slug == null) {
            missing.add(msg.get("seller.missing.slug"));
        }
        if (products.countBySellerId(s.id) == 0) {
            missing.add(msg.get("seller.missing.product"));
        }
        if (!missing.isEmpty()) {
            flash.addFlashAttribute("error", msg.get("seller.missing", String.join(", ", missing)));
            return "redirect:/seller";
        }
        s.status = SellerStatus.PENDING_REVIEW;
        sellers.save(s);
        flash.addFlashAttribute("notice", msg.get("seller.submitted"));
        return "redirect:/seller";
    }

    // ------------------------------------------------------------------ products

    @GetMapping("/seller/products")
    public String products(@AuthenticationPrincipal AppUser me, Model model) {
        Seller s = seller(me);
        model.addAttribute("seller", s);
        model.addAttribute("products", products.findBySellerIdOrderBySortOrderAscIdAsc(s.id));
        return "seller/products";
    }

    @GetMapping("/seller/products/new")
    public String newProduct(@AuthenticationPrincipal AppUser me, Model model) {
        model.addAttribute("seller", seller(me));
        model.addAttribute("product", new Product());
        return "seller/product";
    }

    @GetMapping("/seller/products/{id}")
    public String editProduct(@AuthenticationPrincipal AppUser me, @PathVariable long id, Model model) {
        Seller s = seller(me);
        model.addAttribute("seller", s);
        model.addAttribute("product", products.findByIdAndSellerId(id, s.id).orElseThrow());
        return "seller/product";
    }

    @PostMapping({"/seller/products/new", "/seller/products/{id}"})
    public String saveProduct(@AuthenticationPrincipal AppUser me, @PathVariable(required = false) Long id,
                              @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String description,
                              @RequestParam(defaultValue = "") String price, @RequestParam(defaultValue = "") String unit,
                              @RequestParam(defaultValue = "") String stock, @RequestParam(defaultValue = "false") boolean available,
                              @RequestParam(defaultValue = "false") boolean removePhoto,
                              @RequestParam(name = "photo", required = false) MultipartFile photo,
                              RedirectAttributes flash) {
        Seller s = seller(me);
        Product p = id == null ? new Product() : products.findByIdAndSellerId(id, s.id).orElseThrow();
        try {
            p.sellerId = s.id;
            p.name = required(name, "seller.product.name", 120);
            p.description = orEmpty(optional(description, "seller.product.description", 600));
            p.pricePaise = price.isBlank() ? null : parsePrice(price);
            p.unit = orEmpty(optional(unit, "seller.product.unit", 40));
            p.stock = parseStock(stock);
            p.isAvailable = available;
            p.needsReview = false;
            p.reviewFields = "";
            if (photo != null && !photo.isEmpty()) {
                p.imagePath = uploads.storeAll(s.id, List.of(photo)).get(0).path();
            } else if (removePhoto) {
                p.imagePath = null;
            }
            if (id == null) {
                p.sortOrder = (int) products.countBySellerId(s.id);
            }
            products.save(p);
            contentTranslator.soon();
            flash.addFlashAttribute("notice", msg.get("seller.product.saved", p.name));
            return "redirect:/seller/products";
        } catch (FormError | UploadService.UploadException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return id == null ? "redirect:/seller/products/new" : "redirect:/seller/products/" + id;
        }
    }

    @PostMapping("/seller/products/{id}/delete")
    public String deleteProduct(@AuthenticationPrincipal AppUser me, @PathVariable long id, RedirectAttributes flash) {
        Seller s = seller(me);
        products.findByIdAndSellerId(id, s.id).ifPresent(p -> {
            products.delete(p);
            flash.addFlashAttribute("notice", msg.get("seller.product.removed", p.name));
        });
        return "redirect:/seller/products";
    }

    // ------------------------------------------------------------------ orders

    @GetMapping("/seller/orders")
    public String orders(@AuthenticationPrincipal AppUser me, Model model) {
        Seller s = seller(me);
        List<Order> list = orders.findBySellerIdOrderByCreatedAtDesc(s.id);
        model.addAttribute("seller", s);
        model.addAttribute("orders", list);
        model.addAttribute("items", orderService.itemsFor(list));
        model.addAttribute("earnings", orderService.earnings(s.id));
        return "seller/orders";
    }

    @GetMapping("/seller/orders/{id}")
    public String order(@AuthenticationPrincipal AppUser me, @PathVariable long id, Model model) {
        Seller s = seller(me);
        Order o = orderService.forSeller(s.id, id);
        model.addAttribute("seller", s);
        model.addAttribute("order", o);
        model.addAttribute("items", orderService.items(id));
        model.addAttribute("waPhone", o.customerPhone == null ? null : "91" + o.customerPhone);
        return "seller/order";
    }

    @PostMapping("/seller/orders/{id}/{action}")
    public String orderAction(@AuthenticationPrincipal AppUser me, @PathVariable long id, @PathVariable String action,
                              @RequestParam(defaultValue = "") String note, RedirectAttributes flash) {
        try {
            orderService.sellerAction(seller(me).id, id, action, note);
            flash.addFlashAttribute("notice", msg.get("seller.order.updated", id));
        } catch (OrderService.OrderException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/seller/orders/" + id;
    }

    // ------------------------------------------------------------------ messages

    public record MessageRow(Enquiry enquiry, com.collectivelaunchpad.domain.Reply draft, com.collectivelaunchpad.domain.Reply sent) {}

    @GetMapping("/seller/messages")
    public String messages(@AuthenticationPrincipal AppUser me, @RequestParam(defaultValue = "open") String show, Model model) {
        Seller s = seller(me);
        String lang = Msg.lang();
        List<MessageRow> rows = enquiries.forSeller(s.id).stream()
                .filter(e -> "done".equals(show) != e.isOpen())
                .map(e -> new MessageRow(e, enquiries.currentDraft(e.id), enquiries.lastSent(e.id)))
                .toList();
        boolean translating = false;
        for (MessageRow r : rows) {
            if (r.enquiry().translations != null && r.enquiry().needsTranslationFor(lang)) {
                enquiries.translateInBackground(r.enquiry(), lang);
                translating = true;
            }
        }
        model.addAttribute("seller", s);
        model.addAttribute("rows", rows);
        model.addAttribute("show", show);
        model.addAttribute("waiting", translating
                || rows.stream().anyMatch(r -> r.enquiry().isOpen() && r.draft() == null && r.enquiry().aiError == null));
        return "seller/messages";
    }

    @PostMapping("/seller/messages/{id}/{action}")
    public String messageAction(@AuthenticationPrincipal AppUser me, @PathVariable long id, @PathVariable String action,
                                @RequestParam(defaultValue = "") String reply, RedirectAttributes flash) {
        Seller s = seller(me);
        try {
            enquiries.findForSeller(id, s.id);
            switch (action) {
                case "send" -> {
                    enquiries.approveAndSend(id, reply, me.id());
                    flash.addFlashAttribute("notice", msg.get("seller.messages.sent"));
                }
                case "discard" -> enquiries.discardDraft(id);
                case "redraft" -> enquiries.redraftInBackground(id);
                case "close" -> enquiries.close(id);
                default -> throw new EnquiryService.InvalidEnquiry(msg.get("err.unknownAction"));
            }
        } catch (EnquiryService.InvalidEnquiry e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/seller/messages#enquiry-" + id;
    }

    // ------------------------------------------------------------------ assistant (Claude builds the draft shop)

    @GetMapping("/seller/assistant")
    public String assistant(@AuthenticationPrincipal AppUser me, Model model) {
        Seller s = seller(me);
        Optional<OnboardingSession> session = sessions.findFirstBySellerIdOrderByIdDesc(s.id);
        model.addAttribute("seller", s);
        model.addAttribute("onb", session.orElse(null));
        model.addAttribute("exchanges", session.map(x -> OnboardingController.exchanges(x.messages)).orElse(List.of()));
        model.addAttribute("products", products.findBySellerIdOrderBySortOrderAscIdAsc(s.id));
        model.addAttribute("maxPhotos", UploadService.MAX_FILES_PER_REQUEST);
        return "seller/assistant";
    }

    @PostMapping("/seller/assistant/start")
    public String startAssistant(@AuthenticationPrincipal AppUser me, @RequestParam(defaultValue = "") String notes,
                                 @RequestParam(name = "photos", required = false) List<MultipartFile> photos,
                                 RedirectAttributes flash) {
        Seller s = seller(me);
        try {
            onboarding.startForSeller(groups.findFirstByOrderByIdAsc().orElseThrow(), s, notes, photos);
        } catch (OnboardingService.OnboardingException | UploadService.UploadException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/seller/assistant";
    }

    @PostMapping("/seller/assistant/answer")
    public String answerAssistant(@AuthenticationPrincipal AppUser me, @RequestParam(defaultValue = "") String answer,
                                  @RequestParam(name = "photos", required = false) List<MultipartFile> photos,
                                  RedirectAttributes flash) {
        try {
            onboarding.answer(ownSession(me).id, answer, photos, true);
        } catch (OnboardingService.OnboardingException | UploadService.UploadException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/seller/assistant";
    }

    @PostMapping("/seller/assistant/retry")
    public String retryAssistant(@AuthenticationPrincipal AppUser me, RedirectAttributes flash) {
        try {
            onboarding.retry(ownSession(me).id);
        } catch (OnboardingService.OnboardingException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/seller/assistant";
    }

    private OnboardingSession ownSession(AppUser me) {
        return sessions.findFirstBySellerIdOrderByIdDesc(me.sellerId())
                .orElseThrow(() -> new OnboardingService.OnboardingException(msg.get("err.assistantStart")));
    }

    // ------------------------------------------------------------------ helpers

    static Map<Long, String> labels(List<Order> list) {
        return list.stream().collect(Collectors.toMap(o -> o.id, o -> Money.label(o.totalPaise)));
    }

    private Long parsePrice(String raw) {
        try {
            return Money.parseRupees(raw);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new FormError(msg.get("err.price"));
        }
    }

    private Integer parseStock(String raw) {
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
            throw new FormError(msg.get("err.stock"));
        }
    }

    private String required(String v, String labelKey, int max) {
        String t = optional(v, labelKey, max);
        if (t == null) {
            throw new FormError(msg.get("err.required", msg.get(labelKey)));
        }
        return t;
    }

    private String optional(String v, String labelKey, int max) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String t = v.strip();
        if (t.length() > max) {
            throw new FormError(msg.get("err.tooLong", msg.get(labelKey), max));
        }
        return t;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
