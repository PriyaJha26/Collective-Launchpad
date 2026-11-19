package com.collectivelaunchpad.admin;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.collectivelaunchpad.domain.Enquiry;
import com.collectivelaunchpad.domain.Repositories.EnquiryRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.domain.Entities.EnquiryStatus;
import com.collectivelaunchpad.enquiry.EnquiryService;

/** Customer enquiries with the agent's reply drafts. Nothing is sent until the admin approves. */
@Controller
public class InboxController {

    public record Row(Enquiry enquiry, Seller seller, com.collectivelaunchpad.domain.Reply draft, com.collectivelaunchpad.domain.Reply sent) {}

    private final EnquiryService service;
    private final EnquiryRepository enquiries;
    private final SellerRepository sellers;

    public InboxController(EnquiryService service, EnquiryRepository enquiries, SellerRepository sellers) {
        this.service = service;
        this.enquiries = enquiries;
        this.sellers = sellers;
    }

    @GetMapping("/admin/inbox")
    public String inbox(@RequestParam(defaultValue = "open") String show, Model model) {
        List<Enquiry> list = "done".equals(show)
                ? enquiries.findTop30ByStatusInOrderByCreatedAtDesc(List.of(EnquiryStatus.REPLIED, EnquiryStatus.CLOSED))
                : service.open();
        Map<Long, Seller> byId = sellers.findAllById(list.stream().map(e -> e.sellerId).distinct().toList()).stream()
                .collect(Collectors.toMap(s -> s.id, Function.identity()));
        List<Row> rows = list.stream()
                .sorted((a, b) -> Boolean.compare("high".equals(b.urgency), "high".equals(a.urgency)))
                .map(e -> new Row(e, byId.get(e.sellerId), service.currentDraft(e.id), service.lastSent(e.id)))
                .toList();
        model.addAttribute("rows", rows);
        model.addAttribute("show", show);
        model.addAttribute("waiting", rows.stream().anyMatch(r -> r.enquiry().isOpen() && r.draft() == null && r.enquiry().aiError == null));
        return "admin/inbox";
    }

    @PostMapping("/admin/enquiries/{id}/send")
    public String send(@PathVariable long id, @RequestParam(defaultValue = "") String reply,
                       @org.springframework.security.core.annotation.AuthenticationPrincipal com.collectivelaunchpad.account.AppUser user,
                       RedirectAttributes flash) {
        long adminId = user.id();
        try {
            service.approveAndSend(id, reply, adminId);
            flash.addFlashAttribute("notice", "Reply sent.");
        } catch (EnquiryService.InvalidEnquiry e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/inbox#enquiry-" + id;
    }

    @PostMapping("/admin/enquiries/{id}/discard")
    public String discard(@PathVariable long id) {
        service.discardDraft(id);
        return "redirect:/admin/inbox#enquiry-" + id;
    }

    @PostMapping("/admin/enquiries/{id}/redraft")
    public String redraft(@PathVariable long id) {
        service.redraftInBackground(id);
        return "redirect:/admin/inbox#enquiry-" + id;
    }

    @PostMapping("/admin/enquiries/{id}/close")
    public String close(@PathVariable long id, RedirectAttributes flash) {
        service.close(id);
        flash.addFlashAttribute("notice", "Enquiry closed.");
        return "redirect:/admin/inbox";
    }
}
