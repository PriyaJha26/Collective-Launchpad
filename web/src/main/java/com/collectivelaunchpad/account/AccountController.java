package com.collectivelaunchpad.account;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.collectivelaunchpad.domain.Account;
import com.collectivelaunchpad.domain.Enquiry;
import com.collectivelaunchpad.domain.Group;
import com.collectivelaunchpad.domain.Order;
import com.collectivelaunchpad.domain.Repositories.AccountRepository;
import com.collectivelaunchpad.domain.Repositories.GroupRepository;
import com.collectivelaunchpad.domain.Repositories.OrderRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.enquiry.EnquiryService;
import com.collectivelaunchpad.i18n.Msg;
import com.collectivelaunchpad.shop.OrderService;
import com.collectivelaunchpad.site.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Registration (customer and seller), login page, and the customer's own pages. */
@Controller
public class AccountController {

    private final AccountService accounts;
    private final AccountRepository accountRepo;
    private final GroupRepository groups;
    private final SellerRepository sellers;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final EnquiryService enquiries;
    private final RateLimiter rateLimiter;
    private final SessionLogin sessionLogin;
    private final Msg msg;

    public AccountController(AccountService accounts, AccountRepository accountRepo, GroupRepository groups,
                             SellerRepository sellers, OrderRepository orders, OrderService orderService,
                             EnquiryService enquiries, RateLimiter rateLimiter, SessionLogin sessionLogin, Msg msg) {
        this.sessionLogin = sessionLogin;
        this.msg = msg;
        this.accounts = accounts;
        this.accountRepo = accountRepo;
        this.groups = groups;
        this.sellers = sellers;
        this.orders = orders;
        this.orderService = orderService;
        this.enquiries = enquiries;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    // ------------------------------------------------------------------ customer registration

    @GetMapping("/register")
    public String registerForm(@AuthenticationPrincipal AppUser me) {
        return me != null ? "redirect:/account" : "account/register";
    }

    @PostMapping("/register")
    public String register(HttpServletRequest req, HttpServletResponse res, Model model,
                           @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String phone,
                           @RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String password) {
        if (!rateLimiter.tryAcquire("register:" + req.getRemoteAddr())) {
            model.addAttribute("error", msg.get("err.signupLimit"));
            return "account/register";
        }
        try {
            Account a = accounts.registerCustomer(name, phone, password, email);
            logIn(a, req, res);
            return "redirect:" + afterSignup(req, "/account");
        } catch (AccountService.AccountException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("form", new String[] {name, phone, email});
            return "account/register";
        }
    }

    // ------------------------------------------------------------------ seller registration

    @GetMapping("/register/seller")
    public String sellerForm(@AuthenticationPrincipal AppUser me, Model model) {
        if (me != null && me.isSeller()) {
            return "redirect:/seller";
        }
        return "account/register-seller";
    }

    @PostMapping("/register/seller")
    public String registerSeller(HttpServletRequest req, HttpServletResponse res, Model model,
                                 @AuthenticationPrincipal AppUser me,
                                 @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String phone,
                                 @RequestParam(defaultValue = "") String password,
                                 @RequestParam(defaultValue = "") String shopName, @RequestParam(defaultValue = "") String craft,
                                 @RequestParam(defaultValue = "") String location, @RequestParam(defaultValue = "") String whatsapp) {
        if (!rateLimiter.tryAcquire("register:" + req.getRemoteAddr())) {
            model.addAttribute("error", msg.get("err.signupLimit"));
            return sellerForm(me, model);
        }
        try {
            Group group = groups.findFirstByOrderByIdAsc().orElseThrow();
            Account a = accounts.registerSeller(group,
                    new AccountService.SellerSignup(name, phone, password, shopName, craft, location, whatsapp), me);
            logIn(a, req, res); // refresh the session so the new SELLER role applies at once
            return "redirect:/seller?welcome";
        } catch (AccountService.AccountException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("form", new AccountService.SellerSignup(name, phone, "", shopName, craft, location, whatsapp));
            return sellerForm(me, model);
        }
    }

    // ------------------------------------------------------------------ customer pages

    @GetMapping("/account")
    public String account(@AuthenticationPrincipal AppUser me, Model model) {
        model.addAttribute("account", accountRepo.findById(me.id()).orElseThrow());
        List<Order> mine = orders.findByCustomerIdOrderByCreatedAtDesc(me.id());
        model.addAttribute("orders", mine.stream().limit(5).toList());
        model.addAttribute("shops", shopNames(mine));
        return "account/home";
    }

    @PostMapping("/account/profile")
    public String profile(@AuthenticationPrincipal AppUser me, @RequestParam(defaultValue = "") String name,
                          @RequestParam(defaultValue = "") String email, RedirectAttributes flash) {
        try {
            accounts.updateProfile(me.id(), name, email);
            flash.addFlashAttribute("notice", msg.get("common.saved"));
        } catch (AccountService.AccountException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/account";
    }

    @PostMapping("/account/password")
    public String password(@AuthenticationPrincipal AppUser me, @RequestParam(defaultValue = "") String current,
                           @RequestParam(defaultValue = "") String next, RedirectAttributes flash) {
        try {
            accounts.changePassword(me.id(), current, next);
            flash.addFlashAttribute("notice", msg.get("account.passwordChanged"));
        } catch (AccountService.AccountException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/account";
    }

    @GetMapping("/account/orders")
    public String orders(@AuthenticationPrincipal AppUser me, Model model) {
        List<Order> mine = orders.findByCustomerIdOrderByCreatedAtDesc(me.id());
        model.addAttribute("orders", mine);
        model.addAttribute("shops", shopNames(mine));
        var items = orderService.itemsFor(mine);
        model.addAttribute("items", items);
        return "account/orders";
    }

    @GetMapping("/account/orders/{id}")
    public String order(@AuthenticationPrincipal AppUser me, @PathVariable long id, Model model) {
        Order o = orderService.forCustomer(me.id(), id);
        Seller s = sellers.findById(o.sellerId).orElseThrow();
        model.addAttribute("order", o);
        model.addAttribute("seller", s);
        var items = orderService.items(id);
        model.addAttribute("items", items);
        model.addAttribute("upiLink", Order.UPI.equals(o.paymentMethod) ? OrderService.upiLink(s, o) : null);
        return "account/order";
    }

    @PostMapping("/account/orders/{id}/cancel")
    public String cancel(@AuthenticationPrincipal AppUser me, @PathVariable long id, RedirectAttributes flash) {
        try {
            orderService.cancelByCustomer(me.id(), id);
            flash.addFlashAttribute("notice", msg.get("account.orderCancelled"));
        } catch (OrderService.OrderException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/account/orders/" + id;
    }

    @PostMapping("/account/orders/{id}/paid")
    public String paid(@AuthenticationPrincipal AppUser me, @PathVariable long id, RedirectAttributes flash) {
        try {
            orderService.customerMarkedPaid(me.id(), id);
            flash.addFlashAttribute("notice", msg.get("account.markedPaid"));
        } catch (OrderService.OrderException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/account/orders/" + id;
    }

    @GetMapping("/account/messages")
    public String messages(@AuthenticationPrincipal AppUser me, Model model) {
        List<Enquiry> list = enquiries.forCustomer(me.id());
        model.addAttribute("messages", list);
        model.addAttribute("replies", list.stream().collect(java.util.stream.Collectors.toMap(
                e -> e.id, e -> java.util.Optional.ofNullable(enquiries.lastSent(e.id)))));
        model.addAttribute("shops", sellers.findAllById(list.stream().map(e -> e.sellerId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.id, s -> s)));
        return "account/messages";
    }

    // ------------------------------------------------------------------ helpers

    private java.util.Map<Long, Seller> shopNames(List<Order> list) {
        return sellers.findAllById(list.stream().map(o -> o.sellerId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.id, s -> s));
    }

    /** Logs a just-registered account in, with a fresh session id. */
    private void logIn(Account a, HttpServletRequest req, HttpServletResponse res) {
        sessionLogin.logIn(a, req, res);
    }

    /** Return to checkout (or wherever login was asked for) after signing up. */
    private static String afterSignup(HttpServletRequest req, String fallback) {
        String next = req.getParameter("next");
        return next != null && next.startsWith("/") && !next.startsWith("//") ? next : fallback;
    }
}
