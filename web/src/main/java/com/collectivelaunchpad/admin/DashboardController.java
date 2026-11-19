package com.collectivelaunchpad.admin;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.collectivelaunchpad.agentclient.AgentClient;
import com.collectivelaunchpad.config.AppProperties;
import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Entities.SessionStatus;
import com.collectivelaunchpad.domain.OnboardingSession;
import com.collectivelaunchpad.domain.Repositories.EnquiryRepository;
import com.collectivelaunchpad.domain.Repositories.OnboardingSessionRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.enquiry.EnquiryService;
import com.collectivelaunchpad.mail.MailService;
import com.collectivelaunchpad.site.GroupRequestService;

@Controller
public class DashboardController {

    private final SellerRepository sellers;
    private final OnboardingSessionRepository sessions;
    private final EnquiryRepository enquiries;
    private final MailService mail;
    private final AgentClient agent;
    private final AppProperties props;
    private final GroupRequestService requests;

    public DashboardController(SellerRepository sellers, OnboardingSessionRepository sessions, EnquiryRepository enquiries,
                               MailService mail, AgentClient agent, AppProperties props, GroupRequestService requests) {
        this.requests = requests;
        this.sellers = sellers;
        this.sessions = sessions;
        this.enquiries = enquiries;
        this.mail = mail;
        this.agent = agent;
        this.props = props;
    }

    @GetMapping({"/admin", "/admin/"})
    public String dashboard(Model model) {
        List<Seller> all = sellers.findAllByOrderByCreatedAtDesc();
        Map<Long, Seller> byId = all.stream().collect(Collectors.toMap(s -> s.id, Function.identity()));
        List<OnboardingSession> active = sessions.findByStatusInOrderByUpdatedAtDesc(
                List.of(SessionStatus.WORKING, SessionStatus.NEEDS_INPUT, SessionStatus.FAILED));
        long published = all.stream().filter(Seller::isPublished).count();

        model.addAttribute("sellers", all);
        model.addAttribute("sessions", active.stream().filter(s -> byId.containsKey(s.sellerId)
                && byId.get(s.sellerId).isEditableByAgent()).toList());
        model.addAttribute("sellerById", byId);
        model.addAttribute("published", published);
        model.addAttribute("pendingReview", all.stream().filter(s -> SellerStatus.PENDING_REVIEW.equals(s.status)).count());
        model.addAttribute("openEnquiries", enquiries.countByStatusIn(EnquiryService.OPEN));
        model.addAttribute("openRequests", requests.openCount());
        model.addAttribute("monthlyCost", props.costs().monthlyTotal());
        model.addAttribute("costPerSeller", props.costs().monthlyTotal() / Math.max(1, published));
        model.addAttribute("mailMode", mail.mode());
        model.addAttribute("mailFrom", mail.from());
        model.addAttribute("agentMode", agentMode());
        model.addAttribute("recentEmail", mail.recent());
        return "admin/dashboard";
    }

    private String agentMode() {
        try {
            var h = agent.health();
            return "live".equals(h.path("mode").asText())
                    ? "Claude: " + h.path("models").path("onboarding").asText() + " (onboarding, replies), "
                      + h.path("models").path("classify").asText() + " (classification)"
                    : "Mock answers (the agent service has no Claude API key)";
        } catch (Exception e) {
            return "Agent service not reachable at " + props.agentUrl();
        }
    }
}
