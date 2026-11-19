package com.collectivelaunchpad.site;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.collectivelaunchpad.config.SiteUrls;
import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Group;
import com.collectivelaunchpad.domain.Repositories.GroupRepository;
import com.collectivelaunchpad.domain.Repositories.ProductRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.enquiry.EnquiryService;
import com.collectivelaunchpad.tenant.Tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Public pages. The hostname decides what "/" is: the group page, or one seller's shop. */
@Controller
public class SiteController {

    private final GroupRepository groups;
    private final SellerRepository sellers;
    private final ProductRepository products;
    private final EnquiryService enquiries;
    private final RateLimiter rateLimiter;
    private final SiteUrls urls;
    private final com.collectivelaunchpad.i18n.Msg msg;

    public SiteController(GroupRepository groups, SellerRepository sellers, ProductRepository products,
                          EnquiryService enquiries, RateLimiter rateLimiter, SiteUrls urls, com.collectivelaunchpad.i18n.Msg msg) {
        this.msg = msg;
        this.groups = groups;
        this.sellers = sellers;
        this.products = products;
        this.enquiries = enquiries;
        this.rateLimiter = rateLimiter;
        this.urls = urls;
    }

    @GetMapping("/")
    public String root(HttpServletRequest req, Model model) {
        Tenant tenant = Tenant.of(req);
        if (tenant.isShop()) {
            shopModel(model, tenant.shop(), false);
            model.addAttribute("sent", req.getParameter("sent") != null);
            return "site/shop";
        }
        groupModel(model);
        model.addAttribute("sent", req.getParameter("sent") != null);
        return "site/group";
    }

    /** The group page's model; also used when the "Contact us" form is shown again with an error. */
    public void groupModel(Model model) {
        model.addAttribute("group", group());
        model.addAttribute("shops", sellers.findByStatusOrderByShopNameAsc(SellerStatus.PUBLISHED));
        model.addAttribute("urls", urls);
    }

    @PostMapping("/contact")
    public String contact(HttpServletRequest req, HttpServletResponse res, Model model,
                          @RequestParam(defaultValue = "") String name,
                          @RequestParam(defaultValue = "") String email,
                          @RequestParam(defaultValue = "") String phone,
                          @RequestParam(defaultValue = "") String message,
                          @RequestParam(defaultValue = "") String website) {
        Tenant tenant = Tenant.of(req);
        if (!tenant.isShop()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var form = new EnquiryService.ContactForm(name, email, phone, message);
        if (!website.isBlank()) {
            // Honeypot: a hidden field people never fill. Pretend success, store nothing.
            return "redirect:/?sent=1#contact";
        }
        if (!rateLimiter.tryAcquire(req.getRemoteAddr())) {
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return formError(model, tenant.shop(), form, msg.get("err.rateLimit"));
        }
        try {
            enquiries.receive(tenant.shop(), form);
        } catch (EnquiryService.InvalidEnquiry e) {
            res.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            return formError(model, tenant.shop(), form, e.getMessage());
        }
        return "redirect:/?sent=1#contact";
    }

    private String formError(Model model, Seller shop, EnquiryService.ContactForm form, String error) {
        shopModel(model, shop, false);
        model.addAttribute("formError", error);
        model.addAttribute("form", form);
        return "site/shop";
    }

    /** Also used by the admin preview of unpublished shops. */
    public void shopModel(Model model, Seller shop, boolean preview) {
        model.addAttribute("shop", shop);
        model.addAttribute("products", preview
                ? products.findBySellerIdOrderBySortOrderAscIdAsc(shop.id)
                : products.findBySellerIdAndIsAvailableTrueOrderBySortOrderAscIdAsc(shop.id));
        model.addAttribute("group", group());
        model.addAttribute("mainUrl", urls.mainUrl());
        model.addAttribute("shopHost", shop.slug == null ? msg.get("shop.noAddress") : urls.shopHost(shop.slug));
        model.addAttribute("preview", preview);
    }

    private Group group() {
        return groups.findFirstByOrderByIdAsc().orElseThrow();
    }
}
