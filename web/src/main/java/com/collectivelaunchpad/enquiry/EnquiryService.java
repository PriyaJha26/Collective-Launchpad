package com.collectivelaunchpad.enquiry;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.collectivelaunchpad.agentclient.AgentClient;
import com.collectivelaunchpad.domain.Entities.EnquiryStatus;
import com.collectivelaunchpad.domain.Entities.ReplyStatus;
import com.collectivelaunchpad.domain.Enquiry;
import com.collectivelaunchpad.domain.Group;
import com.collectivelaunchpad.domain.Product;
import com.collectivelaunchpad.domain.Reply;
import com.collectivelaunchpad.domain.Repositories.EnquiryRepository;
import com.collectivelaunchpad.domain.Repositories.GroupRepository;
import com.collectivelaunchpad.domain.Repositories.ProductRepository;
import com.collectivelaunchpad.domain.Repositories.ReplyRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.i18n.Languages;
import com.collectivelaunchpad.i18n.Msg;
import com.collectivelaunchpad.i18n.SellerLanguages;
import com.collectivelaunchpad.i18n.TranslationService;
import com.collectivelaunchpad.mail.MailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Contact form to reply: save, email a copy to the group mailbox, then (in the background)
 * classify and draft. The admin approves, edits or discards; only an approved reply is sent.
 */
@Service
public class EnquiryService {

    private static final Logger log = LoggerFactory.getLogger(EnquiryService.class);
    public static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    public static final Pattern PHONE = Pattern.compile("^[0-9+()\\-\\s]{6,20}$");
    public static final Set<String> OPEN = Set.of(EnquiryStatus.NEW, EnquiryStatus.DRAFTED);

    public record ContactForm(String name, String email, String phone, String message, String topic) {
        public ContactForm(String name, String email, String phone, String message) {
            this(name, email, phone, message, null);
        }
    }

    public static final Set<String> TOPICS = Set.of("question", "discount", "custom", "order");

    public static class InvalidEnquiry extends RuntimeException {
        public InvalidEnquiry(String message) {
            super(message);
        }
    }

    private final EnquiryRepository enquiries;
    private final ReplyRepository replies;
    private final SellerRepository sellers;
    private final ProductRepository products;
    private final GroupRepository groups;
    private final AgentClient agent;
    private final MailService mail;
    private final ObjectMapper mapper;
    private final ExecutorService background;
    private final TranslationService translator;
    private final SellerLanguages sellerLanguages;
    private final Msg msg;

    public EnquiryService(EnquiryRepository enquiries, ReplyRepository replies, SellerRepository sellers,
                          ProductRepository products, GroupRepository groups, AgentClient agent, MailService mail,
                          ObjectMapper mapper, ExecutorService backgroundExecutor, TranslationService translator,
                          SellerLanguages sellerLanguages, Msg msg) {
        this.enquiries = enquiries;
        this.replies = replies;
        this.sellers = sellers;
        this.products = products;
        this.groups = groups;
        this.agent = agent;
        this.mail = mail;
        this.mapper = mapper;
        this.background = backgroundExecutor;
        this.translator = translator;
        this.sellerLanguages = sellerLanguages;
        this.msg = msg;
    }

    private String sellerLanguage(Seller s) {
        return sellerLanguages.of(s);
    }

    // ------------------------------------------------------------------ intake

    public Enquiry receive(Seller seller, ContactForm form) {
        return receive(seller, form, null);
    }

    /** customerUserId is set when the customer is logged in: replies then also show in their account. */
    public Enquiry receive(Seller seller, ContactForm form, Long customerUserId) {
        String message = clip(form.message(), 4000);
        String name = clip(form.name(), 100);
        String email = clip(form.email(), 200);
        String phone = clip(form.phone(), 20);
        if (message == null) {
            throw new InvalidEnquiry(msg.get("err.writeMessage"));
        }
        if (email == null && phone == null && customerUserId == null) {
            throw new InvalidEnquiry(msg.get("err.contactNeeded"));
        }
        if (email != null && !EMAIL.matcher(email).matches()) {
            throw new InvalidEnquiry(msg.get("err.email"));
        }
        if (phone != null && !PHONE.matcher(phone).matches()) {
            throw new InvalidEnquiry(msg.get("err.phone"));
        }
        Enquiry e = new Enquiry();
        e.customerLanguage = Msg.lang();
        e.sellerId = seller.id;
        e.customerName = name;
        e.customerEmail = email;
        e.customerPhone = phone;
        e.message = message;
        e.topic = form.topic() != null && TOPICS.contains(form.topic()) ? form.topic() : null;
        e.customerUserId = customerUserId;
        enquiries.save(e);
        long id = e.id;
        background.submit(() -> {
            notifyGroup(id);
            process(id);
        });
        return e;
    }

    private void notifyGroup(long enquiryId) {
        try {
            Enquiry e = enquiries.findById(enquiryId).orElseThrow();
            Seller s = sellers.findById(e.sellerId).orElseThrow();
            String who = e.customerName == null ? "a customer" : e.customerName;
            mail.send(mail.from(), e.customerEmail, "New enquiry for " + s.shopName + " from " + who,
                    "Shop: " + s.shopName + " (" + s.name + ")\n"
                            + "From: " + who + "\n"
                            + "Email: " + (e.customerEmail == null ? "-" : e.customerEmail) + "\n"
                            + "Phone: " + (e.customerPhone == null ? "-" : e.customerPhone) + "\n\n"
                            + e.message + "\n\n"
                            + "A reply draft will be waiting in the dashboard inbox.");
        } catch (Exception ex) {
            log.warn("Could not email enquiry {} to the group mailbox: {}", enquiryId, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ agent work

    /** Classify (if not done yet) and draft a reply. Never throws; failures are stored on the enquiry. */
    public void process(long enquiryId) {
        Enquiry e = enquiries.findById(enquiryId).orElse(null);
        if (e == null) {
            return;
        }
        if (e.category == null) {
            try {
                ObjectNode req = mapper.createObjectNode();
                req.put("customer_name", e.customerName == null ? "" : e.customerName);
                req.put("message", e.message);
                JsonNode c = agent.classify(req);
                e.category = c.path("category").asText(null);
                e.urgency = c.path("urgency").asText(null);
                e.language = c.path("language").asText(null);
                enquiries.save(e);
            } catch (AgentClient.AgentException ex) {
                log.warn("Classifying enquiry {} failed: {}", enquiryId, ex.getMessage());
            }
        }
        if (e.translations == null) {
            Seller s = sellers.findById(e.sellerId).orElseThrow();
            translateMessage(e, java.util.List.of(sellerLanguage(s), "en"));
        }
        try {
            JsonNode d = agent.draftReply(draftRequest(e));
            Reply r = new Reply();
            r.enquiryId = e.id;
            r.body = d.path("reply").asText();
            r.checkPoints = d.path("check_points").isArray() ? d.get("check_points") : mapper.createArrayNode();
            r.draftedBy = "agent";
            r.status = ReplyStatus.DRAFT;
            replies.save(r);
            e.status = EnquiryStatus.DRAFTED;
            e.aiError = null;
        } catch (AgentClient.AgentException ex) {
            log.warn("Drafting a reply to enquiry {} failed: {}", enquiryId, ex.getMessage());
            e.aiError = ex.getMessage();
        }
        enquiries.save(e);
    }

    /** Adds the message in the given languages (skipping the one it was written in). Never throws. */
    private void translateMessage(Enquiry e, java.util.List<String> targets) {
        var wanted = targets.stream().filter(t -> e.needsTranslationFor(t)).distinct().toList();
        if (wanted.isEmpty()) {
            return;
        }
        try {
            var result = translator.translate(java.util.Map.of("text", e.message), wanted);
            Enquiry fresh = enquiries.findById(e.id).orElseThrow();
            fresh.translations = translator.flatten(result, fresh.translations);
            enquiries.save(fresh);
            e.translations = fresh.translations;
        } catch (AgentClient.AgentException ex) {
            log.warn("Translating enquiry {} failed: {}", e.id, ex.getMessage());
        }
    }

    /** The reader's language has no translation yet (e.g. the seller switched language): add it in the background. */
    public void translateInBackground(Enquiry e, String lang) {
        if (e.needsTranslationFor(lang)) {
            long id = e.id;
            background.submit(() -> enquiries.findById(id).ifPresent(fresh -> translateMessage(fresh, java.util.List.of(lang))));
        }
    }

    private ObjectNode draftRequest(Enquiry e) {
        Seller s = sellers.findById(e.sellerId).orElseThrow();
        Group g = groups.findFirstByOrderByIdAsc().orElseThrow();
        ObjectNode req = mapper.createObjectNode();
        req.put("group_name", g.name);
        req.put("reply_language", sellerLanguage(s));
        ObjectNode seller = req.putObject("seller");
        seller.put("name", s.name);
        seller.put("shop_name", s.shopName == null ? s.name : s.shopName);
        seller.put("bio", s.bio == null ? "" : s.bio);
        seller.put("location", s.location == null ? "" : s.location);
        ArrayNode catalogue = req.putArray("catalogue");
        for (Product p : products.findBySellerIdOrderBySortOrderAscIdAsc(s.id)) {
            ObjectNode item = catalogue.addObject();
            item.put("name", p.name);
            item.put("description", p.description);
            if (p.pricePaise == null) {
                item.putNull("price_rupees");
            } else {
                item.put("price_rupees", p.pricePaise / 100.0);
            }
            item.put("unit", p.unit);
            item.put("available", p.isAvailable);
        }
        ObjectNode enquiry = req.putObject("enquiry");
        enquiry.put("customer_name", e.customerName == null ? "" : e.customerName);
        enquiry.put("message", e.message);
        if (e.category != null) {
            enquiry.put("category", e.category);
        }
        if (e.topic != null) {
            enquiry.put("topic", e.topic);
        }
        return req;
    }

    public void redraftInBackground(long enquiryId) {
        Enquiry e = find(enquiryId);
        replies.findFirstByEnquiryIdAndStatusInOrderByIdDesc(e.id, Set.of(ReplyStatus.DRAFT)).ifPresent(r -> {
            r.status = ReplyStatus.DISCARDED;
            replies.save(r);
        });
        e.status = EnquiryStatus.NEW;
        e.aiError = null;
        enquiries.save(e);
        background.submit(() -> process(enquiryId));
    }

    // ------------------------------------------------------------------ admin decisions

    /** Approve the (possibly edited) reply and send it from the group mailbox. */
    public Reply approveAndSend(long enquiryId, String body, long adminId) {
        Enquiry e = find(enquiryId);
        if (!e.isOpen()) {
            throw new InvalidEnquiry(msg.get("err.enquiryClosed"));
        }
        if (!e.hasEmail() && e.customerUserId == null) {
            throw new InvalidEnquiry(msg.get("err.phoneOnly"));
        }
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            throw new InvalidEnquiry(msg.get("err.replyEmpty"));
        }
        if (text.length() > 6000) {
            throw new InvalidEnquiry(msg.get("err.replyLong"));
        }
        String writtenIn = Msg.lang();
        String customerLang = Languages.orEnglish(e.customerLanguage);
        com.fasterxml.jackson.databind.JsonNode translated = null;
        String forCustomer = text;
        if (!customerLang.equals(writtenIn)) {
            try {
                translated = translator.flatten(translator.translate(java.util.Map.of("text", text), java.util.List.of(customerLang)), null);
            } catch (AgentClient.AgentException ex) {
                log.warn("Translating the reply to enquiry {} failed: {}", enquiryId, ex.getMessage());
                throw new InvalidEnquiry(msg.get("err.replyTranslate"));
            }
            if (translated.path(customerLang).asText("").isBlank()) {
                log.warn("The agent returned no {} translation for the reply to enquiry {}", customerLang, enquiryId);
                throw new InvalidEnquiry(msg.get("err.replyTranslate"));
            }
            forCustomer = translated.path(customerLang).asText();
        }
        Reply r = replies.findFirstByEnquiryIdAndStatusInOrderByIdDesc(e.id, Set.of(ReplyStatus.DRAFT, ReplyStatus.APPROVED))
                .orElseGet(() -> {
                    Reply fresh = new Reply();
                    fresh.enquiryId = e.id;
                    fresh.draftedBy = "admin";
                    return fresh;
                });
        r.body = text;
        r.language = writtenIn;
        r.translations = translated;
        r.status = ReplyStatus.APPROVED;
        r.approvedBy = adminId;
        replies.save(r);

        Seller s = sellers.findById(e.sellerId).orElseThrow();
        // Logged-in customers read the reply in their account; email is sent too when they gave one.
        MailService.Result result = e.hasEmail()
                ? mail.send(e.customerEmail, mail.from(), msg.in(customerLang, "email.replySubject", s.shopNameIn(customerLang)), forCustomer)
                : new MailService.Result(true, "IN_APP");
        if (!result.ok()) {
            r.sendError = result.detail();
            replies.save(r);
            throw new InvalidEnquiry(msg.get("err.emailFailed", result.detail()));
        }
        r.status = ReplyStatus.SENT;
        r.sentAt = OffsetDateTime.now();
        r.sendError = null;
        replies.save(r);
        e.status = EnquiryStatus.REPLIED;
        enquiries.save(e);
        return r;
    }

    public void discardDraft(long enquiryId) {
        Enquiry e = find(enquiryId);
        replies.findFirstByEnquiryIdAndStatusInOrderByIdDesc(e.id, Set.of(ReplyStatus.DRAFT, ReplyStatus.APPROVED)).ifPresent(r -> {
            r.status = ReplyStatus.DISCARDED;
            replies.save(r);
        });
        if (e.isOpen()) {
            e.status = EnquiryStatus.NEW;
            enquiries.save(e);
        }
    }

    public void close(long enquiryId) {
        Enquiry e = find(enquiryId);
        e.status = EnquiryStatus.CLOSED;
        enquiries.save(e);
    }

    public Enquiry find(long id) {
        return enquiries.findById(id).orElseThrow(() -> new InvalidEnquiry(msg.get("err.enquiryNotFound")));
    }

    public List<Enquiry> forSeller(long sellerId) {
        return enquiries.findBySellerIdOrderByCreatedAtDesc(sellerId);
    }

    public List<Enquiry> forCustomer(long customerUserId) {
        return enquiries.findByCustomerUserIdOrderByCreatedAtDesc(customerUserId);
    }

    /** Throws unless the enquiry belongs to this seller. */
    public Enquiry findForSeller(long enquiryId, long sellerId) {
        Enquiry e = find(enquiryId);
        if (!java.util.Objects.equals(e.sellerId, sellerId)) {
            throw new InvalidEnquiry(msg.get("err.enquiryNotFound"));
        }
        return e;
    }

    public List<Enquiry> open() {
        return enquiries.findByStatusInOrderByCreatedAtDesc(OPEN);
    }

    public Reply currentDraft(long enquiryId) {
        return replies.findFirstByEnquiryIdAndStatusInOrderByIdDesc(enquiryId, Set.of(ReplyStatus.DRAFT, ReplyStatus.APPROVED)).orElse(null);
    }

    public Reply lastSent(long enquiryId) {
        return replies.findFirstByEnquiryIdAndStatusInOrderByIdDesc(enquiryId, Set.of(ReplyStatus.SENT)).orElse(null);
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
