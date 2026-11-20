package com.collectivelaunchpad.site;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.collectivelaunchpad.domain.GroupRequest;
import com.collectivelaunchpad.domain.Repositories.GroupRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Public forms on the group's main site (shop hostnames never serve these paths):
 * "Sell with us" (/join) for sellers who want a shop, and "Contact us" for everyone else.
 */
@Controller
public class GroupContactController {

    private final GroupRequestService service;
    private final GroupRepository groups;
    private final RateLimiter rateLimiter;
    private final SiteController site;
    private final com.collectivelaunchpad.i18n.Msg msg;

    public GroupContactController(GroupRequestService service, GroupRepository groups, RateLimiter rateLimiter, SiteController site,
                                  com.collectivelaunchpad.i18n.Msg msg) {
        this.msg = msg;
        this.service = service;
        this.groups = groups;
        this.rateLimiter = rateLimiter;
        this.site = site;
    }

    @GetMapping("/join")
    public String joinForm(HttpServletRequest req, Model model) {
        model.addAttribute("group", groups.findFirstByOrderByIdAsc().orElseThrow());
        model.addAttribute("sent", req.getParameter("sent") != null);
        return "site/join";
    }

    @PostMapping("/join")
    public String join(HttpServletRequest req, HttpServletResponse res, Model model,
                       @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String phone,
                       @RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String location,
                       @RequestParam(defaultValue = "") String craft,
                       @RequestParam(defaultValue = "") String message, @RequestParam(defaultValue = "") String website) {
        var form = new GroupRequestService.Form(name, email, phone, location, craft, message);
        String error = handle(GroupRequest.JOIN, form, website, req, res);
        if (error == null) {
            return "redirect:/join?sent=1";
        }
        joinForm(req, model);
        model.addAttribute("formError", error);
        model.addAttribute("form", form);
        return "site/join";
    }

    @PostMapping("/contact-us")
    public String contactUs(HttpServletRequest req, HttpServletResponse res, Model model,
                            @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String phone,
                            @RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String message,
                            @RequestParam(defaultValue = "") String website) {
        var form = new GroupRequestService.Form(name, email, phone, null, null, message);
        String error = handle(GroupRequest.QUESTION, form, website, req, res);
        if (error == null) {
            return "redirect:/?sent=1#contact";
        }
        site.groupModel(model);
        model.addAttribute("contactError", error);
        model.addAttribute("contactForm", form);
        return "site/group";
    }

    /** Returns an error message for the form, or null on success. */
    private String handle(String kind, GroupRequestService.Form form, String honeypot, HttpServletRequest req, HttpServletResponse res) {
        if (!honeypot.isBlank()) {
            return null; // a bot filled the hidden field: pretend success, store nothing
        }
        if (!rateLimiter.tryAcquire("group:" + req.getRemoteAddr())) {
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return msg.get("err.rateLimit");
        }
        try {
            service.submit(kind, form);
            return null;
        } catch (GroupRequestService.InvalidRequest e) {
            res.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            return e.getMessage();
        }
    }
}
