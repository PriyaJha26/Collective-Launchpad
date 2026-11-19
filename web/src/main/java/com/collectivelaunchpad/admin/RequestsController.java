package com.collectivelaunchpad.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.collectivelaunchpad.onboarding.OnboardingService;
import com.collectivelaunchpad.site.GroupRequestService;
import com.collectivelaunchpad.site.GroupRequestService.InvalidRequest;

/** Join requests and questions from the group's main site. */
@Controller
public class RequestsController {

    private final GroupRequestService service;

    public RequestsController(GroupRequestService service) {
        this.service = service;
    }

    @GetMapping("/admin/requests")
    public String list(@RequestParam(defaultValue = "open") String show, Model model) {
        model.addAttribute("requests", "done".equals(show) ? service.closed() : service.open());
        model.addAttribute("show", show);
        model.addAttribute("languages", OnboardingService.LANGUAGES);
        return "admin/requests";
    }

    @PostMapping("/admin/requests/{id}/status")
    public String status(@PathVariable long id, @RequestParam String status, RedirectAttributes flash) {
        try {
            service.setStatus(id, status);
        } catch (InvalidRequest e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/requests#request-" + id;
    }

    @PostMapping("/admin/requests/{id}/reply")
    public String reply(@PathVariable long id, @RequestParam(defaultValue = "") String reply, RedirectAttributes flash) {
        try {
            service.reply(id, reply);
            flash.addFlashAttribute("notice", "Reply sent.");
        } catch (InvalidRequest e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/requests#request-" + id;
    }

    /** Opens "Add a seller" filled in from a join request. */
    @GetMapping("/admin/requests/{id}/onboard")
    public String onboard(@PathVariable long id) {
        service.find(id);
        return "redirect:/admin/sellers/new?request=" + id;
    }
}
