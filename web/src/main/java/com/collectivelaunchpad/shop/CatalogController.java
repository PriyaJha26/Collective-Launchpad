package com.collectivelaunchpad.shop;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.collectivelaunchpad.account.AppUser;
import com.collectivelaunchpad.domain.Account;
import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Order;
import com.collectivelaunchpad.domain.Product;
import com.collectivelaunchpad.domain.Repositories.AccountRepository;
import com.collectivelaunchpad.domain.Repositories.ProductRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.enquiry.EnquiryService;
import com.collectivelaunchpad.i18n.Msg;
import com.collectivelaunchpad.site.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Shopping on the main domain: browse all products, product pages, messages to a seller, the
 * cart and checkout. Shop subdomains link here for buying, so one login works everywhere.
 */
@Controller
public class CatalogController {

    private final SellerRepository sellers;
    private final ProductRepository products;
    private final AccountRepository accounts;
    private final OrderService orders;
    private final EnquiryService enquiries;
    private final RateLimiter rateLimiter;
    private final Msg msg;

    public CatalogController(SellerRepository sellers, ProductRepository products, AccountRepository accounts,
                             OrderService orders, EnquiryService enquiries, RateLimiter rateLimiter, Msg msg) {
        this.msg = msg;
        this.sellers = sellers;
        this.products = products;
        this.accounts = accounts;
        this.orders = orders;
        this.enquiries = enquiries;
        this.rateLimiter = rateLimiter;
    }

    // ------------------------------------------------------------------ browsing

    @GetMapping("/products")
    public String products(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) Long shop,
                           @RequestParam(defaultValue = "false") boolean stock,
                           @RequestParam(defaultValue = "") String sort, Model model) {
        List<Seller> live = sellers.findByStatusOrderByShopNameAsc(SellerStatus.PUBLISHED);
        Map<Long, Seller> byId = live.stream().collect(Collectors.toMap(s -> s.id, Function.identity()));
        List<Long> ids = shop != null && byId.containsKey(shop) ? List.of(shop) : List.copyOf(byId.keySet());
        String needle = q.strip().toLowerCase(Locale.ROOT);
        Comparator<Product> order = switch (sort) {
            case "price-low" -> Comparator.comparing(p -> p.pricePaise == null ? Long.MAX_VALUE : p.pricePaise);
            case "price-high" -> Comparator.comparing((Product p) -> p.pricePaise == null ? 0L : p.pricePaise).reversed();
            default -> null;
        };
        var list = (ids.isEmpty() ? List.<Product>of() : products
                .findBySellerIdInAndIsAvailableTrueOrderBySellerIdAscSortOrderAscIdAsc(ids)).stream()
                .filter(p -> !stock || p.canBeOrdered())
                .filter(p -> needle.isEmpty() || contains(p.name, needle) || contains(p.description, needle)
                        || contains(byId.get(p.sellerId).shopName, needle));
        model.addAttribute("products", order == null ? list.toList() : list.sorted(order).toList());
        model.addAttribute("shopsById", byId);
        model.addAttribute("shops", live);
        model.addAttribute("q", q);
        model.addAttribute("shop", shop);
        model.addAttribute("stock", stock);
        model.addAttribute("sort", sort);
        return "shop/products";
    }

    @GetMapping("/p/{id}")
    public String product(@PathVariable long id, Model model) {
        Product p = liveProduct(id);
        model.addAttribute("product", p);
        model.addAttribute("seller", sellers.findById(p.sellerId).orElseThrow());
        return "shop/product";
    }

    // ------------------------------------------------------------------ message a seller

    @GetMapping("/shops/{slug}/message")
    public String messageForm(@PathVariable String slug, @RequestParam(required = false) Long product,
                              @RequestParam(defaultValue = "question") String topic, Model model) {
        Seller s = liveSeller(slug);
        model.addAttribute("seller", s);
        model.addAttribute("topic", EnquiryService.TOPICS.contains(topic) ? topic : "question");
        if (product != null) {
            products.findByIdAndSellerId(product, s.id).ifPresent(p -> model.addAttribute("product", p));
        }
        return "shop/message";
    }

    @PostMapping("/shops/{slug}/message")
    public String message(@PathVariable String slug, @AuthenticationPrincipal AppUser me, HttpServletRequest req,
                          @RequestParam(defaultValue = "question") String topic, @RequestParam(defaultValue = "") String message,
                          @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String email,
                          @RequestParam(defaultValue = "") String phone, @RequestParam(required = false) Long product,
                          Model model, RedirectAttributes flash) {
        Seller s = liveSeller(slug);
        if (!rateLimiter.tryAcquire("message:" + req.getRemoteAddr())) {
            model.addAttribute("error", msg.get("err.rateLimit"));
            return messageForm(slug, product, topic, model);
        }
        String text = message;
        if (product != null) {
            var p = products.findByIdAndSellerId(product, s.id);
            if (p.isPresent()) {
                text = msg.get("shop.message.about", p.get().name) + "\n\n" + message;
            }
        }
        try {
            if (me != null) {
                Account a = accounts.findById(me.id()).orElseThrow();
                enquiries.receive(s, new EnquiryService.ContactForm(a.name, a.email, a.phone, message.isBlank() ? "" : text, topic), a.id);
            } else {
                enquiries.receive(s, new EnquiryService.ContactForm(name, email, phone, message.isBlank() ? "" : text, topic));
            }
        } catch (EnquiryService.InvalidEnquiry e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("form", new String[] {name, email, phone, message});
            return messageForm(slug, product, topic, model);
        }
        flash.addFlashAttribute("notice", msg.get(me != null ? "shop.message.sentAccount" : "shop.message.sent"));
        return me != null ? "redirect:/account/messages" : "redirect:/shops/" + slug + "/message?sent";
    }

    // ------------------------------------------------------------------ cart

    @GetMapping("/cart")
    public String cart(HttpSession session, Model model) {
        model.addAttribute("carts", orders.shopCarts(Cart.of(session)));
        return "shop/cart";
    }

    @PostMapping("/cart/add")
    public String add(@RequestParam long productId, @RequestParam(defaultValue = "1") int quantity, HttpSession session,
                      RedirectAttributes flash) {
        Product p = liveProduct(productId);
        if (!p.canBeOrdered()) {
            flash.addFlashAttribute("error", msg.get("err.cannotOrder"));
            return "redirect:/p/" + productId;
        }
        Cart.of(session).add(productId, Math.min(Math.max(quantity, 1), Cart.MAX_QTY));
        flash.addFlashAttribute("notice", msg.get("cart.added", p.name));
        return "redirect:/cart";
    }

    @PostMapping("/cart/update")
    public String update(@RequestParam long productId, @RequestParam int quantity, HttpSession session) {
        Cart.of(session).set(productId, quantity);
        return "redirect:/cart";
    }

    @PostMapping("/cart/remove")
    public String remove(@RequestParam long productId, HttpSession session) {
        Cart.of(session).remove(productId);
        return "redirect:/cart";
    }

    // ------------------------------------------------------------------ checkout (login required)

    @GetMapping("/checkout/{sellerId}")
    public String checkout(@PathVariable long sellerId, @AuthenticationPrincipal AppUser me, HttpSession session,
                           Model model, RedirectAttributes flash) {
        try {
            model.addAttribute("cart", orders.shopCart(Cart.of(session), sellerId));
        } catch (OrderService.OrderException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/cart";
        }
        if (!model.containsAttribute("form")) {
            Account a = accounts.findById(me.id()).orElseThrow();
            model.addAttribute("form", new OrderService.CheckoutForm(a.name, a.phone, "delivery", "", "", Order.COD));
        }
        return "shop/checkout";
    }

    @PostMapping("/checkout/{sellerId}")
    public String placeOrder(@PathVariable long sellerId, @AuthenticationPrincipal AppUser me, HttpSession session,
                             @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String phone,
                             @RequestParam(defaultValue = "delivery") String delivery, @RequestParam(defaultValue = "") String address,
                             @RequestParam(defaultValue = "") String note, @RequestParam(defaultValue = "cod") String payment,
                             Model model, RedirectAttributes flash) {
        var form = new OrderService.CheckoutForm(name, phone, delivery, address, note, payment);
        try {
            Order o = orders.place(me, Cart.of(session), sellerId, form);
            flash.addFlashAttribute("notice", msg.get("checkout.placed"));
            return "redirect:/account/orders/" + o.id;
        } catch (OrderService.OrderException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("form", form);
            return checkout(sellerId, me, session, model, flash);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Product liveProduct(long id) {
        Product p = products.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Seller s = sellers.findById(p.sellerId).orElseThrow();
        if (!s.isPublished() || !p.isAvailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return p;
    }

    private Seller liveSeller(String slug) {
        return sellers.findBySlugAndStatus(slug, SellerStatus.PUBLISHED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }
}
