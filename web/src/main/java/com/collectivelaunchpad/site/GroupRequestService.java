package com.collectivelaunchpad.site;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.collectivelaunchpad.domain.GroupRequest;
import com.collectivelaunchpad.domain.Repositories.GroupRequestRepository;
import com.collectivelaunchpad.enquiry.EnquiryService;
import com.collectivelaunchpad.mail.MailService;
import com.collectivelaunchpad.onboarding.OnboardingService;

/** Join requests and questions sent to the group admin from the main site. */
@Service
public class GroupRequestService {

    private static final Logger log = LoggerFactory.getLogger(GroupRequestService.class);
    public static final Set<String> OPEN = Set.of(GroupRequest.NEW, GroupRequest.IN_PROGRESS);

    public record Form(String name, String email, String phone, String location, String language, String craft, String message) {}

    public static class InvalidRequest extends RuntimeException {
        public InvalidRequest(String message) {
            super(message);
        }
    }

    private final GroupRequestRepository requests;
    private final MailService mail;
    private final ExecutorService background;
    private final com.collectivelaunchpad.agentclient.AgentClient agent;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.collectivelaunchpad.i18n.Msg msg;

    public GroupRequestService(GroupRequestRepository requests, MailService mail, ExecutorService backgroundExecutor,
                               com.collectivelaunchpad.agentclient.AgentClient agent,
                               com.fasterxml.jackson.databind.ObjectMapper mapper, com.collectivelaunchpad.i18n.Msg msg) {
        this.msg = msg;
        this.requests = requests;
        this.mail = mail;
        this.background = backgroundExecutor;
        this.agent = agent;
        this.mapper = mapper;
    }

    public GroupRequest submit(String kind, Form f) {
        boolean join = GroupRequest.JOIN.equals(kind);
        GroupRequest r = new GroupRequest();
        r.kind = join ? GroupRequest.JOIN : GroupRequest.QUESTION;
        r.name = clip(f.name(), 100);
        r.email = clip(f.email(), 200);
        r.phone = clip(f.phone(), 20);
        r.location = clip(f.location(), 120);
        r.language = f.language() != null && OnboardingService.LANGUAGES.containsKey(f.language()) ? f.language() : null;
        r.craft = clip(f.craft(), 300);
        r.message = clip(f.message(), 4000);

        if (r.name == null) {
            throw new InvalidRequest(msg.get("err.enterName"));
        }
        if (r.email == null && r.phone == null) {
            throw new InvalidRequest(msg.get("err.contactUs"));
        }
        if (r.email != null && !EnquiryService.EMAIL.matcher(r.email).matches()) {
            throw new InvalidRequest(msg.get("err.email"));
        }
        if (r.phone != null && !EnquiryService.PHONE.matcher(r.phone).matches()) {
            throw new InvalidRequest(msg.get("err.phone"));
        }
        if (join && r.craft == null) {
            throw new InvalidRequest(msg.get("err.craftNeeded"));
        }
        if (!join && r.message == null) {
            throw new InvalidRequest(msg.get("err.writeMessage"));
        }
        requests.save(r);
        long id = r.id;
        background.submit(() -> {
            translate(id);
            notifyAdmin(id);
        });
        return r;
    }

    /** Requests written in a local language get an English translation for the superadmin. */
    void translate(long id) {
        GroupRequest r = requests.findById(id).orElse(null);
        if (r == null) {
            return;
        }
        var fields = mapper.createObjectNode();
        java.util.Map<String, String> source = new java.util.LinkedHashMap<>();
        source.put("name", r.name);
        source.put("location", r.location);
        source.put("craft", r.craft);
        source.put("message", r.message);
        source.forEach((k, v) -> {
            if (v != null && !v.chars().allMatch(c -> c < 128)) {
                fields.put(k, v);
            }
        });
        if (fields.isEmpty()) {
            return; // all English / Latin letters already
        }
        try {
            var req = mapper.createObjectNode();
            req.set("fields", fields);
            var res = agent.translate(req);
            var out = mapper.createObjectNode();
            res.path("translations").forEach(t -> out.put(t.path("field").asText(), t.path("english").asText()));
            r.translationEn = out;
            r.sourceLanguage = res.path("source_language").asText(null);
            requests.save(r);
        } catch (Exception e) {
            log.warn("Could not translate group request {}: {}", id, e.getMessage());
        }
    }

    private void notifyAdmin(long id) {
        try {
            GroupRequest r = requests.findById(id).orElseThrow();
            String subject = r.isJoin() ? "Request to join: " + r.name : "Message for the group from " + r.name;
            StringBuilder body = new StringBuilder();
            body.append(r.isJoin() ? "Someone wants a shop in the group.\n\n" : "New message from the group website.\n\n");
            body.append("Name: ").append(r.name).append('\n');
            body.append("Phone: ").append(r.phone == null ? "-" : r.phone).append('\n');
            body.append("Email: ").append(r.email == null ? "-" : r.email).append('\n');
            if (r.isJoin()) {
                body.append("Location: ").append(r.location == null ? "-" : r.location).append('\n');
                body.append("Language: ").append(r.language == null ? "-" : OnboardingService.LANGUAGES.get(r.language)).append('\n');
                body.append("Makes / sells: ").append(r.craft).append('\n');
            }
            if (r.message != null) {
                body.append('\n').append(r.message).append('\n');
            }
            if (r.translationEn != null && !r.translationEn.isEmpty()) {
                body.append("\nIn English:\n");
                r.translationEn.fields().forEachRemaining(e ->
                        body.append("  ").append(e.getKey()).append(": ").append(e.getValue().asText()).append('\n'));
            }
            body.append("\nOpen \"Requests\" in the admin dashboard to reply or start onboarding.");
            mail.send(mail.from(), r.email, subject, body.toString());
        } catch (Exception e) {
            log.warn("Could not email group request {}: {}", id, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ admin

    public GroupRequest find(long id) {
        return requests.findById(id).orElseThrow(() -> new InvalidRequest("Request not found."));
    }

    public List<GroupRequest> open() {
        return requests.findByStatusInOrderByCreatedAtDesc(OPEN);
    }

    public List<GroupRequest> closed() {
        return requests.findTop50ByStatusInOrderByCreatedAtDesc(Set.of(GroupRequest.DONE, GroupRequest.DECLINED));
    }

    public long openCount() {
        return requests.countByStatusIn(OPEN);
    }

    public void setStatus(long id, String status) {
        if (!Set.of(GroupRequest.NEW, GroupRequest.IN_PROGRESS, GroupRequest.DONE, GroupRequest.DECLINED).contains(status)) {
            throw new InvalidRequest("Unknown status.");
        }
        GroupRequest r = find(id);
        r.status = status;
        requests.save(r);
    }

    /** Reply by email from the group mailbox. */
    public void reply(long id, String text) {
        GroupRequest r = find(id);
        if (!r.hasEmail()) {
            throw new InvalidRequest("This person left only a phone number. Call or WhatsApp them instead.");
        }
        String body = text == null ? "" : text.strip();
        if (body.isEmpty() || body.length() > 6000) {
            throw new InvalidRequest("Write a reply (up to 6,000 characters).");
        }
        MailService.Result result = mail.send(r.email, mail.from(),
                r.isJoin() ? "Your request to join" : "Re: your message", body);
        if (!result.ok()) {
            throw new InvalidRequest("The email could not be sent: " + result.detail());
        }
        r.replySent = body;
        r.repliedAt = OffsetDateTime.now();
        if (GroupRequest.NEW.equals(r.status)) {
            r.status = GroupRequest.IN_PROGRESS;
        }
        requests.save(r);
    }

    /** Called when the admin starts onboarding from a join request. */
    public void linkSeller(long id, long sellerId) {
        requests.findById(id).ifPresent(r -> {
            r.sellerId = sellerId;
            r.status = GroupRequest.DONE;
            requests.save(r);
        });
    }

    /** Notes for the "Add a seller" form, built from what the applicant wrote. */
    public static String onboardingNotes(GroupRequest r) {
        StringBuilder sb = new StringBuilder("From their request to join:\n");
        sb.append("Makes / sells: ").append(r.craft == null ? "-" : r.craft).append('\n');
        if (r.location != null) {
            sb.append("Location: ").append(r.location).append('\n');
        }
        if (r.message != null) {
            sb.append(r.message).append('\n');
        }
        return sb.toString().strip();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }
}
