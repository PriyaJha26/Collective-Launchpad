package com.collectivelaunchpad.admin;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.collectivelaunchpad.account.AccountService;
import com.collectivelaunchpad.account.AppUser;
import com.collectivelaunchpad.domain.Account;
import com.collectivelaunchpad.domain.Order;
import com.collectivelaunchpad.domain.Repositories.AccountRepository;
import com.collectivelaunchpad.domain.Repositories.OrderRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.shop.OrderService;

/** Superadmin: all accounts, all orders, earnings per shop, and logins for sellers they added. */
@Controller
public class MarketplaceAdminController {

    public record ShopEarnings(Seller seller, OrderService.Earnings earnings) {}

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final SellerRepository sellers;
    private final OrderRepository orders;
    private final OrderService orderService;

    public MarketplaceAdminController(AccountRepository accounts, AccountService accountService, SellerRepository sellers,
                                      OrderRepository orders, OrderService orderService) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.sellers = sellers;
        this.orders = orders;
        this.orderService = orderService;
    }

    @GetMapping("/admin/users")
    public String users(Model model) {
        List<Account> all = accounts.findAllByOrderByCreatedAtDesc();
        model.addAttribute("accounts", all);
        model.addAttribute("shops", sellers.findAll().stream().collect(Collectors.toMap(s -> s.id, Function.identity())));
        return "admin/users";
    }

    @PostMapping("/admin/users/{id}/reset-password")
    public String resetPassword(@PathVariable long id, RedirectAttributes flash) {
        try {
            String pw = accountService.resetPassword(id);
            Account a = accounts.findById(id).orElseThrow();
            flash.addFlashAttribute("notice", "New password for " + a.name + " (" + a.login() + "): " + pw
                    + "  — give it to them; they can change it after logging in.");
        } catch (AccountService.AccountException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/active")
    public String setActive(@PathVariable long id, @RequestParam boolean active, @AuthenticationPrincipal AppUser me,
                            RedirectAttributes flash) {
        try {
            accountService.setActive(id, active, me.id());
        } catch (AccountService.AccountException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    /** Give a seller added by the superadmin their own login (phone + password). */
    @PostMapping("/admin/sellers/{id}/login")
    public String createSellerLogin(@PathVariable long id, @RequestParam(defaultValue = "") String phone,
                                    RedirectAttributes flash) {
        try {
            String pw = accountService.createSellerLogin(id, phone, null);
            flash.addFlashAttribute("notice", "Login created. Mobile: " + AccountService.normalizePhone(phone).orElse(phone)
                    + "  Password: " + pw + "  — give these to the seller; they can change the password later.");
        } catch (AccountService.AccountException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/sellers/" + id;
    }

    @GetMapping("/admin/orders")
    public String orders(Model model) {
        List<Order> recent = orders.findTop100ByOrderByCreatedAtDesc();
        Map<Long, Seller> shops = sellers.findAll().stream().collect(Collectors.toMap(s -> s.id, Function.identity()));
        model.addAttribute("orders", recent);
        model.addAttribute("shops", shops);
        model.addAttribute("earnings", shops.values().stream()
                .filter(Seller::isPublished)
                .map(s -> new ShopEarnings(s, orderService.earnings(s.id)))
                .sorted((a, b) -> Long.compare(b.earnings().paidTotal(), a.earnings().paidTotal()))
                .toList());
        return "admin/orders";
    }
}
