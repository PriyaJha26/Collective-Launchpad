package com.collectivelaunchpad.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.collectivelaunchpad.agentclient.AgentClient;
import com.collectivelaunchpad.domain.Entities.EnquiryStatus;
import com.collectivelaunchpad.domain.Entities.ReplyStatus;
import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Enquiry;
import com.collectivelaunchpad.domain.Product;
import com.collectivelaunchpad.domain.Reply;
import com.collectivelaunchpad.domain.Repositories.EmailLogRepository;
import com.collectivelaunchpad.domain.Repositories.EnquiryRepository;
import com.collectivelaunchpad.domain.Repositories.GroupRepository;
import com.collectivelaunchpad.domain.Repositories.ProductRepository;
import com.collectivelaunchpad.domain.Repositories.ReplyRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.site.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class WebIntegrationTest extends IntegrationTestBase {

    @TestConfiguration
    static class CleanDatabase {
        @Bean
        FlywayMigrationStrategy cleanThenMigrate() {
            return (Flyway flyway) -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SellerRepository sellers;
    @Autowired ProductRepository products;
    @Autowired EnquiryRepository enquiries;
    @Autowired ReplyRepository replies;
    @Autowired GroupRepository groups;
    @Autowired EmailLogRepository emailLog;
    @Autowired com.collectivelaunchpad.domain.Repositories.GroupRequestRepository groupRequests;
    @Autowired RateLimiter rateLimiter;
    @Autowired ObjectMapper mapper;
    @MockitoBean AgentClient agent;
    @Autowired com.collectivelaunchpad.domain.Repositories.AccountRepository accounts;
    @Autowired com.collectivelaunchpad.account.LoginThrottle loginThrottle;

    /** The seeded superadmin as a logged-in principal. */
    RequestPostProcessor superadmin() {
        var a = accounts.findByEmailIgnoreCase("admin@example.test").orElseThrow();
        return user(com.collectivelaunchpad.account.AppUser.of(a));
    }

    static final String MAIN = "example.test";
    static final String TOKEN = "test-token";

    @BeforeEach
    void reset() throws Exception {
        // Plain deletes in dependency order (TRUNCATE ... CASCADE would also empty users, incl. the superadmin).
        for (String table : new String[] {"order_items", "orders", "replies", "enquiries", "group_requests",
                "onboarding_sessions", "products"}) {
            jdbc.execute("delete from " + table);
        }
        jdbc.execute("delete from users where role <> 'superadmin'");
        jdbc.execute("delete from sellers");
        jdbc.execute("delete from email_log");
        loginThrottle.reset("127.0.0.1");
        rateLimiter.reset();
        when(agent.classify(any())).thenReturn(mapper.readTree("{\"category\":\"order\",\"urgency\":\"normal\",\"language\":\"en\"}"));
        when(agent.draftReply(any())).thenReturn(mapper.readTree("{\"reply\":\"Namaste Ravi\",\"check_points\":[\"Confirm Saturday\"]}"));
        when(agent.onboardingTurn(any())).thenReturn(mapper.readTree(
                "{\"status\":\"needs_input\",\"appended\":[],\"pending_tool_results\":[],\"question\":\"What is the price?\"}"));
        when(agent.health()).thenReturn(mapper.readTree("{\"mode\":\"mock\"}"));
        // Like the agent's mock mode: "[hi] text".
        when(agent.translateTo(any())).thenAnswer(inv -> {
            com.fasterxml.jackson.databind.JsonNode req = inv.getArgument(0);
            var out = mapper.createObjectNode();
            out.put("source_language", "en");
            var results = out.putObject("results");
            req.path("targets").forEach(t -> {
                var r = results.putObject(t.asText());
                req.path("fields").fields().forEachRemaining(f -> r.put(f.getKey(), "[" + t.asText() + "] " + f.getValue().asText()));
            });
            return out;
        });
    }

    static RequestPostProcessor host(String host) {
        return r -> {
            r.setServerName(host);
            return r;
        };
    }

    Seller seller(String slug, String status, String bio) {
        Seller s = new Seller();
        s.groupId = groups.findFirstByOrderByIdAsc().orElseThrow().id;
        s.slug = slug;
        s.name = "Meena Devi";
        s.shopName = "Meena's Kitchen";
        s.bio = bio;
        s.language = "hi";
        s.status = status;
        sellers.save(s);
        Product p = new Product();
        p.sellerId = s.id;
        p.name = "Besan ladoo";
        p.pricePaise = 30000L;
        p.unit = "per kg";
        products.save(p);
        return s;
    }

    // ------------------------------------------------------------------ hostname routing

    @Test
    void mainDomainShowsGroupPage() throws Exception {
        seller("meena", SellerStatus.PUBLISHED, "Hi");
        mvc.perform(get("/").with(host(MAIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Crafts of India")))
                .andExpect(content().string(containsString("meena.example.test")))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void wwwRedirectsToMainDomain() throws Exception {
        mvc.perform(get("/some/path").queryParam("a", "1").with(host("www." + MAIN)))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "http://example.test:8080/some/path?a=1"));
    }

    @Test
    void unknownNestedForeignAndUnpublishedHostsAre404() throws Exception {
        seller("draftshop", SellerStatus.DRAFT, "x");
        seller("hiddenshop", SellerStatus.HIDDEN, "x");
        for (String h : new String[] {"nobody." + MAIN, "a.b." + MAIN, "draftshop." + MAIN, "hiddenshop." + MAIN, "example.com", "1.2.3.4"}) {
            mvc.perform(get("/").with(host(h))).andExpect(status().isNotFound());
        }
    }

    @Test
    void publishedShopRendersWithEscapedText() throws Exception {
        seller("meena", SellerStatus.PUBLISHED, "<script>alert(1)</script> Fresh sweets");
        mvc.perform(get("/").with(host("meena." + MAIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Meena&#39;s Kitchen")))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt; Fresh sweets")))
                .andExpect(content().string(not(containsString("<script>alert(1)"))))
                .andExpect(content().string(containsString("₹300")));
    }

    @Test
    void shopHostServesOnlyShopPaths() throws Exception {
        seller("meena", SellerStatus.PUBLISHED, "x");
        mvc.perform(get("/admin").with(host("meena." + MAIN))).andExpect(status().isNotFound());
        mvc.perform(get("/login").with(host("meena." + MAIN))).andExpect(status().isNotFound());
        mvc.perform(get("/css/app.css").with(host("meena." + MAIN))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ certificate check

    @Test
    void tlsCheckAllowsOnlyRealHostnames() throws Exception {
        seller("meena", SellerStatus.PUBLISHED, "x");
        seller("draftshop", SellerStatus.DRAFT, "x");
        String[][] cases = {
                {MAIN, "200"}, {"www." + MAIN, "200"}, {"meena." + MAIN, "200"},
                {"draftshop." + MAIN, "404"}, {"random." + MAIN, "404"}, {"a.b." + MAIN, "404"}, {"evil.com", "404"},
        };
        for (String[] c : cases) {
            // Caddy calls over the Docker network, so the Host header is the service name.
            mvc.perform(get("/internal/tls-check").queryParam("domain", c[0]).with(host("web")))
                    .andExpect(status().is(Integer.parseInt(c[1])));
        }
    }

    // ------------------------------------------------------------------ admin, login, CSRF

    @Test
    void adminNeedsLogin() throws Exception {
        mvc.perform(get("/admin").with(host(MAIN)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void loginWithSeededAdmin() throws Exception {
        mvc.perform(post("/login").with(host(MAIN)).with(csrf())
                        .param("login", "admin@example.test").param("password", "correct-horse-battery"))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(authenticated().withUsername("admin@example.test"));
        mvc.perform(post("/login").with(host(MAIN)).with(csrf())
                        .param("login", "admin@example.test").param("password", "wrong"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void adminFormsNeedCsrfToken() throws Exception {
        Seller s = seller("meena", SellerStatus.PENDING_REVIEW, "x");
        mvc.perform(post("/admin/sellers/" + s.id + "/publish").with(host(MAIN)).with(superadmin()))
                .andExpect(status().isForbidden());
        assertThat(sellers.findById(s.id).orElseThrow().status).isEqualTo(SellerStatus.PENDING_REVIEW);

        mvc.perform(post("/admin/sellers/" + s.id + "/publish").with(host(MAIN)).with(csrf()).with(superadmin()))
                .andExpect(status().isFound());
        Seller published = sellers.findById(s.id).orElseThrow();
        assertThat(published.status).isEqualTo(SellerStatus.PUBLISHED);
        assertThat(published.verifiedAt).isNotNull();
    }

    @Test
    void dashboardAndEditorRender() throws Exception {
        Seller s = seller("meena", SellerStatus.PENDING_REVIEW, "x");
        var admin = superadmin();
        mvc.perform(get("/admin").with(host(MAIN)).with(admin))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Meena&#39;s Kitchen")));
        mvc.perform(get("/admin/sellers/" + s.id).with(host(MAIN)).with(admin))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Publish")));
        mvc.perform(get("/admin/sellers/" + s.id + "/preview").with(host(MAIN)).with(admin))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Preview")));
        mvc.perform(get("/admin/inbox").with(host(MAIN)).with(admin)).andExpect(status().isOk());
        mvc.perform(get("/admin/sellers/new").with(host(MAIN)).with(admin)).andExpect(status().isOk());
    }

    @Test
    void editingRollsBackOnInvalidInput() throws Exception {
        Seller s = seller("meena", SellerStatus.PENDING_REVIEW, "x");
        long productId = products.findBySellerIdOrderBySortOrderAscIdAsc(s.id).get(0).id;
        var admin = superadmin();
        mvc.perform(post("/admin/sellers/" + s.id).with(host(MAIN)).with(csrf()).with(admin)
                        .param("slug", "meena").param("name", "Meena Devi").param("shopName", "Renamed")
                        .param("products[0].id", Long.toString(productId)).param("products[0].name", "Ladoo")
                        .param("products[0].price", "not a number"))
                .andExpect(status().isFound());
        assertThat(sellers.findById(s.id).orElseThrow().shopName).isEqualTo("Meena's Kitchen");

        mvc.perform(post("/admin/sellers/" + s.id).with(host(MAIN)).with(csrf()).with(admin)
                        .param("slug", "meena").param("name", "Meena Devi").param("shopName", "Renamed")
                        .param("products[0].id", Long.toString(productId)).param("products[0].name", "Ladoo")
                        .param("products[0].price", "12.50").param("products[0].available", "true"))
                .andExpect(status().isFound());
        assertThat(sellers.findById(s.id).orElseThrow().shopName).isEqualTo("Renamed");
        assertThat(products.findById(productId).orElseThrow().pricePaise).isEqualTo(1250L);
    }

    // ------------------------------------------------------------------ onboarding upload

    @Test
    void onboardingAcceptsOnlyRealImagesAndStartsTheAgent() throws Exception {
        var admin = superadmin();
        var fake = new MockMultipartFile("photos", "photo.jpg", "image/jpeg", "not really a jpeg".getBytes());
        mvc.perform(multipart("/admin/sellers/new").file(fake).param("name", "Meena").param("language", "hi")
                        .with(host(MAIN)).with(csrf()).with(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is not a JPG, PNG or WebP photo")));
        assertThat(sellers.count()).isZero();

        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
        var real = new MockMultipartFile("photos", "photo.jpg", "image/jpeg", jpeg);
        mvc.perform(multipart("/admin/sellers/new").file(real).param("name", "Meena").param("language", "hi")
                        .param("notes", "Ladoo 300/kg").with(host(MAIN)).with(csrf()).with(admin))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/admin/onboarding/*"));
        var captor = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(agent, timeout(5000)).onboardingTurn(captor.capture());
        var request = captor.getValue();
        String firstText = request.path("input").get(0).path("text").asText();
        assertThat(firstText).contains("Seller's name: Meena").contains("Group: Crafts of India").contains("Ladoo 300/kg");
        // The stored photo reference is expanded to base64 for the agent.
        assertThat(request.path("input").get(2).path("source").path("type").asText()).isEqualTo("base64");
        assertThat(sellers.findAll()).singleElement().satisfies(s -> assertThat(s.status).isEqualTo(SellerStatus.DRAFT));
    }

    // ------------------------------------------------------------------ contact form

    @Test
    void contactFormSavesEnquiryAndDraftsReply() throws Exception {
        Seller s = seller("meena", SellerStatus.PUBLISHED, "x");
        mvc.perform(post("/contact").with(host("meena." + MAIN))
                        .param("name", "Ravi").param("email", "ravi@example.com").param("message", "2 kg ladoo for Saturday"))
                .andExpect(redirectedUrl("/?sent=1#contact"));
        Enquiry e = enquiries.findAll().get(0);
        assertThat(e.sellerId).isEqualTo(s.id);
        // atLeastOnce: background drafting started by an earlier test may still be finishing.
        verify(agent, timeout(5000).atLeastOnce()).draftReply(any());
        org.awaitility.Awaitility.await().until(() -> EnquiryStatus.DRAFTED.equals(enquiries.findById(e.id).orElseThrow().status));
        assertThat(enquiries.findById(e.id).orElseThrow().category).isEqualTo("order");
        assertThat(replies.findByEnquiryIdOrderByIdDesc(e.id)).singleElement()
                .satisfies(r -> assertThat(r.status).isEqualTo(ReplyStatus.DRAFT));
    }

    @Test
    void contactFormValidationHoneypotAndRateLimit() throws Exception {
        seller("meena", SellerStatus.PUBLISHED, "x");
        String shop = "meena." + MAIN;
        mvc.perform(post("/contact").with(host(shop)).param("message", "hello"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("email address or phone number")));
        mvc.perform(post("/contact").with(host(shop)).param("email", "x@example.com").param("message", "buy now").param("website", "spam.example"))
                .andExpect(redirectedUrl("/?sent=1#contact"));
        assertThat(enquiries.count()).isZero();
        // The contact form is not served on the main domain.
        mvc.perform(post("/contact").with(host(MAIN)).param("email", "x@example.com").param("message", "hi"))
                .andExpect(status().isNotFound());

        rateLimiter.reset();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/contact").with(host(shop)).param("phone", "9876543210").param("message", "hi " + i))
                    .andExpect(status().isFound());
        }
        mvc.perform(post("/contact").with(host(shop)).param("phone", "9876543210").param("message", "again"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void approvedReplyIsSentFromGroupMailbox() throws Exception {
        Seller s = seller("meena", SellerStatus.PUBLISHED, "x");
        Enquiry e = new Enquiry();
        e.sellerId = s.id;
        e.customerName = "Ravi";
        e.customerEmail = "ravi@example.com";
        e.message = "Do you deliver?";
        e.status = EnquiryStatus.DRAFTED;
        enquiries.save(e);
        Reply r = new Reply();
        r.enquiryId = e.id;
        r.body = "draft";
        r.draftedBy = "agent";
        r.status = ReplyStatus.DRAFT;
        replies.save(r);

        mvc.perform(post("/admin/enquiries/" + e.id + "/send").param("reply", "Yes, within the village. – Meena")
                        .with(host(MAIN)).with(csrf()).with(superadmin()))
                .andExpect(status().isFound());
        assertThat(enquiries.findById(e.id).orElseThrow().status).isEqualTo(EnquiryStatus.REPLIED);
        Reply sent = replies.findById(r.id).orElseThrow();
        assertThat(sent.status).isEqualTo(ReplyStatus.SENT);
        assertThat(sent.body).isEqualTo("Yes, within the village. – Meena");
        assertThat(sent.approvedBy).isNotNull();
        assertThat(emailLog.findTop10ByOrderByIdDesc()).anySatisfy(m -> {
            assertThat(m.toAddress).isEqualTo("ravi@example.com");
            assertThat(m.fromAddress).isEqualTo("hello@example.test");
        });
    }

    // ------------------------------------------------------------------ the agent's internal API

    @Test
    void internalApiNeedsToken() throws Exception {
        Seller s = seller(null, SellerStatus.DRAFT, "x");
        mvc.perform(post("/internal/agent/sellers/" + s.id + "/address").contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"meena\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/agent/sellers/" + s.id + "/address").header("X-Internal-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"meena\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentToolsWorkOnDraftsOnly() throws Exception {
        seller("meena", SellerStatus.PUBLISHED, "x");
        Seller draft = seller(null, SellerStatus.DRAFT, "x");
        products.deleteAll(products.findBySellerIdOrderBySortOrderAscIdAsc(draft.id));
        String base = "/internal/agent/sellers/" + draft.id;

        mvc.perform(post(base + "/submit").header("X-Internal-Token", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("shop address")));

        mvc.perform(post(base + "/address").header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"meena\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.alternatives[0]").exists());
        mvc.perform(post(base + "/address").header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"hello\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(base + "/address").header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"Meena Devi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("meena-devi.example.test"));

        String product = """
                {"name":"Puran poli","description":"","price_rupees":40.5,"unit":"each","photo_id":null,"unsure_fields":["unit"]}""";
        mvc.perform(post(base + "/products").header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(product))
                .andExpect(status().isOk()).andExpect(jsonPath("$.product_id").isNumber());
        Product p = products.findBySellerIdOrderBySortOrderAscIdAsc(draft.id).get(0);
        assertThat(p.pricePaise).isEqualTo(4050L);
        assertThat(p.needsReview).isTrue();
        assertThat(p.reviewFieldSet()).containsExactly("unit");

        mvc.perform(post(base + "/products").header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"price_rupees\":null,\"unit\":\"\",\"photo_id\":\"0123456789abcdef0123456789abcdef.jpg\",\"unsure_fields\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("Unknown photo id")));

        mvc.perform(patch(base + "/products/" + p.id).header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":null,\"price_rupees\":45,\"unsure_fields\":[]}"))
                .andExpect(status().isOk());
        assertThat(products.findById(p.id).orElseThrow().pricePaise).isEqualTo(4500L);
        assertThat(products.findById(p.id).orElseThrow().name).isEqualTo("Puran poli");

        mvc.perform(post(base + "/profile").header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shop_name\":\"Meena's Kitchen\",\"bio\":\"I cook.\",\"location\":null,\"unsure_fields\":[\"location\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post(base + "/submit").header("X-Internal-Token", TOKEN)).andExpect(status().isOk());
        assertThat(sellers.findById(draft.id).orElseThrow().status).isEqualTo(SellerStatus.PENDING_REVIEW);

        // Once published, the agent can no longer change the shop.
        Seller live = sellers.findById(draft.id).orElseThrow();
        live.status = SellerStatus.PUBLISHED;
        sellers.save(live);
        mvc.perform(post(base + "/profile").header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shop_name\":\"Hacked\",\"bio\":\"x\",\"location\":null,\"unsure_fields\":[]}"))
                .andExpect(status().isConflict());
        assertThat(sellers.findById(draft.id).orElseThrow().shopName).isEqualTo("Meena's Kitchen");
    }

    // ------------------------------------------------------------------ join requests and group contact

    @Test
    void joinFormSavesRequestOnMainDomainOnly() throws Exception {
        mvc.perform(get("/join").with(host(MAIN)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Sell with us")));
        mvc.perform(get("/join").with(host("sample." + MAIN))).andExpect(status().isNotFound());

        mvc.perform(post("/join").with(host(MAIN)).param("name", "Lakshmi SHG").param("phone", "9876543210"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("what you make")));
        mvc.perform(post("/join").with(host(MAIN)).param("name", "Lakshmi SHG").param("phone", "9876543210")
                        .param("location", "Kanchipuram").param("language", "ta").param("craft", "Silk sarees")
                        .param("message", "<b>12 weavers</b>"))
                .andExpect(redirectedUrl("/join?sent=1"));
        var r = groupRequests.findAll().get(0);
        assertThat(r.kind).isEqualTo("join");
        assertThat(r.language).isEqualTo("ta");
        org.awaitility.Awaitility.await().until(() -> emailLog.findTop10ByOrderByIdDesc().stream()
                .anyMatch(m -> m.subject.equals("Request to join: Lakshmi SHG")));

        // Admin sees it escaped, and "Start onboarding" pre-fills the form and links the new seller.
        var admin = superadmin();
        mvc.perform(get("/admin/requests").with(host(MAIN)).with(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;b&gt;12 weavers&lt;/b&gt;")));
        mvc.perform(get("/admin/sellers/new").param("request", r.id.toString()).with(host(MAIN)).with(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"Lakshmi SHG\"")))
                .andExpect(content().string(containsString("Makes / sells: Silk sarees")));
        mvc.perform(multipart("/admin/sellers/new").param("name", "Lakshmi SHG").param("language", "ta")
                        .param("notes", "Silk sarees").param("requestId", r.id.toString())
                        .with(host(MAIN)).with(csrf()).with(admin))
                .andExpect(status().isFound());
        var linked = groupRequests.findById(r.id).orElseThrow();
        assertThat(linked.status).isEqualTo("done");
        assertThat(linked.sellerId).isEqualTo(sellers.findAll().get(0).id);
    }

    @Test
    void contactUsFormOnGroupPage() throws Exception {
        mvc.perform(post("/contact-us").with(host(MAIN)).param("name", "Asha").param("message", "Hi"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("phone number or an email")))
                .andExpect(content().string(containsString("Crafts of India")));
        mvc.perform(post("/contact-us").with(host(MAIN)).param("name", "Asha").param("email", "asha@example.com")
                        .param("message", "Do you take bulk orders for weddings?"))
                .andExpect(redirectedUrl("/?sent=1#contact"));
        mvc.perform(post("/contact-us").with(host(MAIN)).param("name", "Bot").param("email", "x@example.com")
                        .param("message", "spam").param("website", "http://spam"))
                .andExpect(redirectedUrl("/?sent=1#contact"));
        assertThat(groupRequests.findAll()).singleElement().satisfies(r -> assertThat(r.kind).isEqualTo("question"));

        long id = groupRequests.findAll().get(0).id;
        mvc.perform(post("/admin/requests/" + id + "/reply").param("reply", "Yes, we do. Call us.")
                        .with(host(MAIN)).with(csrf()).with(superadmin()))
                .andExpect(status().isFound());
        assertThat(groupRequests.findById(id).orElseThrow().status).isEqualTo("in_progress");
        assertThat(emailLog.findTop10ByOrderByIdDesc()).anySatisfy(m -> assertThat(m.toAddress).isEqualTo("asha@example.com"));
        // Admin pages stay protected.
        mvc.perform(get("/admin/requests").with(host(MAIN))).andExpect(status().isFound());
    }

    // ================================================================== marketplace

    @Autowired com.collectivelaunchpad.account.AccountService accountService;
    @Autowired com.collectivelaunchpad.domain.Repositories.OrderRepository orderRepo;

    RequestPostProcessor as(com.collectivelaunchpad.domain.Account a) {
        return user(com.collectivelaunchpad.account.AppUser.of(accounts.findById(a.id).orElseThrow()));
    }

    com.collectivelaunchpad.domain.Account sellerLogin(Seller s, String phone) {
        accountService.createSellerLogin(s.id, phone, "sellerpass1");
        return accounts.findByPhone(phone).orElseThrow();
    }

    @Test
    void customerRegistersAndLogsInWithAnyPhoneFormat() throws Exception {
        mvc.perform(post("/register").with(host(MAIN)).with(csrf())
                        .param("name", "Ravi").param("phone", "+91 " + "91234-56780").param("password", "secret123"))
                .andExpect(redirectedUrl("/account"))
                .andExpect(authenticated().withUsername("9123456780"));
        var ravi = accounts.findByPhone("9123456780").orElseThrow();
        assertThat(ravi.role).isEqualTo("customer");

        mvc.perform(post("/register").with(host(MAIN)).with(csrf())
                        .param("name", "Copy").param("phone", "9123456780").param("password", "secret123"))
                .andExpect(content().string(containsString("already registered")));
        mvc.perform(post("/register").with(host(MAIN)).with(csrf())
                        .param("name", "Short").param("phone", "9000000009").param("password", "abc"))
                .andExpect(content().string(containsString("at least 8")));

        mvc.perform(post("/login").with(host(MAIN)).with(csrf()).param("login", "9123456780").param("password", "secret123"))
                .andExpect(redirectedUrl("/account"));

        // Each role only reaches its own area.
        mvc.perform(get("/seller").with(host(MAIN)).with(as(ravi))).andExpect(status().isForbidden());
        mvc.perform(get("/admin").with(host(MAIN)).with(as(ravi))).andExpect(status().isForbidden());
        mvc.perform(get("/account").with(host(MAIN)).with(as(ravi))).andExpect(status().isOk());
        mvc.perform(get("/account").with(host(MAIN))).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void sellerRegistersSetsUpShopAndGoesLiveAfterApproval() throws Exception {
        mvc.perform(post("/register/seller").with(host(MAIN)).with(csrf())
                        .param("name", "Sunita").param("phone", "9123456780").param("password", "secret123")
                        .param("language", "mr").param("craft", "Mango and lemon pickles"))
                .andExpect(redirectedUrl("/seller?welcome"));
        var sunita = accounts.findByPhone("9123456780").orElseThrow();
        assertThat(sunita.role).isEqualTo("seller");
        Seller shop = sellers.findById(sunita.sellerId).orElseThrow();
        assertThat(shop.status).isEqualTo(SellerStatus.DRAFT);
        assertThat(shop.registeredBy).isEqualTo("self");
        var me = as(sunita);

        mvc.perform(get("/seller").with(host(MAIN)).with(me)).andExpect(status().isOk())
                .andExpect(content().string(containsString("अजून लाइव्ह नाही")));
        mvc.perform(post("/seller/shop/submit").with(host(MAIN)).with(csrf()).with(me))
                .andExpect(flash().attribute("error", containsString("किमान एक वस्तू")));

        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
        mvc.perform(multipart("/seller/products/new").file(new MockMultipartFile("photo", "p.jpg", "image/jpeg", jpeg))
                        .param("name", "Mango pickle").param("price", "120").param("unit", "per jar")
                        .param("stock", "3").param("available", "true").with(host(MAIN)).with(csrf()).with(me))
                .andExpect(redirectedUrl("/seller/products"));
        Product p = products.findBySellerIdOrderBySortOrderAscIdAsc(shop.id).get(0);
        assertThat(p.pricePaise).isEqualTo(12000L);
        assertThat(p.stock).isEqualTo(3);
        assertThat(p.imagePath).startsWith("sellers/" + shop.id + "/");

        mvc.perform(post("/seller/shop").with(host(MAIN)).with(csrf()).with(me)
                        .param("shopName", "Sunita's Pickles").param("slug", "sunita").param("upiId", "not-an-upi"))
                .andExpect(flash().attribute("error", containsString("UPI ID")));
        mvc.perform(post("/seller/shop").with(host(MAIN)).with(csrf()).with(me)
                        .param("shopName", "Sunita's Pickles").param("slug", "sunita").param("upiId", "sunita@okaxis").param("language", "mr"))
                .andExpect(flash().attribute("notice", "जतन झाले."));
        mvc.perform(post("/seller/shop/submit").with(host(MAIN)).with(csrf()).with(me)).andExpect(status().isFound());
        assertThat(sellers.findById(shop.id).orElseThrow().status).isEqualTo(SellerStatus.PENDING_REVIEW);
        String marathi = mvc.perform(get("/seller").with(host(MAIN)).with(me)).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(marathi).contains("lang=\"mr\"").contains("मंजुरीची वाट");

        // Hidden until the superadmin approves.
        mvc.perform(get("/").with(host("sunita." + MAIN))).andExpect(status().isNotFound());
        mvc.perform(post("/admin/sellers/" + shop.id + "/publish").with(host(MAIN)).with(csrf()).with(superadmin()))
                .andExpect(status().isFound());
        mvc.perform(get("/").with(host("sunita." + MAIN))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Buy")))
                .andExpect(content().string(containsString("Only 3 left")));
        mvc.perform(get("/products").with(host(MAIN)).param("q", "mango"))
                .andExpect(content().string(containsString("Mango pickle")));

        // A seller cannot touch another seller's product.
        Seller other = seller("meena", SellerStatus.PUBLISHED, "x");
        long othersProduct = products.findBySellerIdOrderBySortOrderAscIdAsc(other.id).get(0).id;
        mvc.perform(get("/seller/products/" + othersProduct).with(host(MAIN)).with(me)).andExpect(status().isNotFound());
        mvc.perform(post("/seller/products/" + othersProduct + "/delete").with(host(MAIN)).with(csrf()).with(me));
        assertThat(products.findById(othersProduct)).isPresent();
    }

    @Test
    void customerOrdersPaysSellerDirectlyAndStockIsKept() throws Exception {
        Seller s = seller("meena", SellerStatus.PUBLISHED, "x");
        s.upiId = "meena@okaxis";
        sellers.save(s);
        Product p = products.findBySellerIdOrderBySortOrderAscIdAsc(s.id).get(0);
        p.stock = 3;
        products.save(p);
        var meena = sellerLogin(s, "9000000001");
        var ravi = accountService.registerCustomer("Ravi", "9123456780", "secret123", null);
        var session = new org.springframework.mock.web.MockHttpSession();

        // Anyone can fill a cart; checkout needs a login.
        mvc.perform(post("/cart/add").session(session).with(host(MAIN)).with(csrf()).param("productId", p.id.toString()).param("quantity", "2"))
                .andExpect(redirectedUrl("/cart"));
        mvc.perform(get("/cart").session(session).with(host(MAIN))).andExpect(content().string(containsString("₹600")));
        mvc.perform(get("/checkout/" + s.id).session(session).with(host(MAIN))).andExpect(redirectedUrlPattern("**/login"));

        mvc.perform(post("/checkout/" + s.id).session(session).with(host(MAIN)).with(csrf()).with(as(ravi))
                        .param("name", "Ravi").param("phone", "9123456780").param("delivery", "delivery")
                        .param("address", "Main road, Wardha 442001").param("payment", "upi"))
                .andExpect(redirectedUrlPattern("/account/orders/*"));
        var order = orderRepo.findByCustomerIdOrderByCreatedAtDesc(ravi.id).get(0);
        assertThat(order.totalPaise).isEqualTo(60000L);
        assertThat(order.status).isEqualTo("placed");
        assertThat(products.findById(p.id).orElseThrow().stock).isEqualTo(1);
        mvc.perform(get("/account/orders/" + order.id).with(host(MAIN)).with(as(ravi)))
                .andExpect(content().string(containsString("upi://pay?pa=meena%40okaxis")))
                .andExpect(content().string(containsString("am=600.00")));

        // Ordering more than is left fails and changes nothing.
        mvc.perform(post("/cart/add").session(session).with(host(MAIN)).with(csrf()).param("productId", p.id.toString()).param("quantity", "5"));
        mvc.perform(post("/checkout/" + s.id).session(session).with(host(MAIN)).with(csrf()).with(as(ravi))
                        .param("name", "Ravi").param("phone", "9123456780").param("delivery", "pickup").param("payment", "cod"))
                .andExpect(content().string(containsString("Only 1")));
        assertThat(orderRepo.findByCustomerIdOrderByCreatedAtDesc(ravi.id)).hasSize(1);
        assertThat(products.findById(p.id).orElseThrow().stock).isEqualTo(1);

        // Other customers and other sellers cannot see or change the order.
        var asha = accountService.registerCustomer("Asha", "9000000002", "secret123", null);
        mvc.perform(get("/account/orders/" + order.id).with(host(MAIN)).with(as(asha))).andExpect(status().isNotFound());
        Seller other = seller("other", SellerStatus.PUBLISHED, "x");
        var otherSeller = sellerLogin(other, "9000000003");
        mvc.perform(get("/seller/orders/" + order.id).with(host(MAIN)).with(as(otherSeller))).andExpect(status().isNotFound());
        mvc.perform(post("/seller/orders/" + order.id + "/accept").with(host(MAIN)).with(csrf()).with(as(otherSeller)));
        assertThat(orderRepo.findById(order.id).orElseThrow().status).isEqualTo("placed");

        // The seller accepts, the customer says they paid, the seller confirms: it counts as earned.
        mvc.perform(post("/seller/orders/" + order.id + "/accept").with(host(MAIN)).with(csrf()).with(as(meena)))
                .andExpect(flash().attribute("notice", "ऑर्डर #" + order.id + " अपडेट हो गया।"));
        mvc.perform(post("/account/orders/" + order.id + "/cancel").with(host(MAIN)).with(csrf()).with(as(ravi)))
                .andExpect(flash().attribute("error", containsString("already accepted")));
        mvc.perform(post("/account/orders/" + order.id + "/paid").with(host(MAIN)).with(csrf()).with(as(ravi)));
        mvc.perform(get("/seller/orders").with(host(MAIN)).with(as(meena)))
                .andExpect(content().string(containsString("ग्राहक के अनुसार भुगतान हुआ")));
        mvc.perform(post("/seller/orders/" + order.id + "/paid").with(host(MAIN)).with(csrf()).with(as(meena)));
        mvc.perform(get("/seller").with(host(MAIN)).with(as(meena)))
                .andExpect(content().string(containsString("कुल कमाई ₹600")));
        mvc.perform(get("/admin/orders").with(host(MAIN)).with(superadmin()))
                .andExpect(content().string(containsString("₹600")));

        // Declining gives the stock back.
        session = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/cart/add").session(session).with(host(MAIN)).with(csrf()).param("productId", p.id.toString()).param("quantity", "1"));
        mvc.perform(post("/checkout/" + s.id).session(session).with(host(MAIN)).with(csrf()).with(as(ravi))
                .param("name", "Ravi").param("phone", "9123456780").param("delivery", "pickup").param("payment", "cod"));
        assertThat(products.findById(p.id).orElseThrow().stock).isZero();
        long second = orderRepo.findByCustomerIdOrderByCreatedAtDesc(ravi.id).get(0).id;
        mvc.perform(post("/seller/orders/" + second + "/decline").param("note", "Sold out today").with(host(MAIN)).with(csrf()).with(as(meena)));
        assertThat(products.findById(p.id).orElseThrow().stock).isEqualTo(1);
    }

    @Test
    void replyIsNotSentWhenItCannotBeTranslatedForTheCustomer() throws Exception {
        Seller s = seller("meena", SellerStatus.PUBLISHED, "x");
        var meena = sellerLogin(s, "9000000001");
        var ravi = accountService.registerCustomer("Ravi", "9123456780", "secret123", null);
        mvc.perform(post("/shops/meena/message").with(host(MAIN)).with(csrf()).with(as(ravi))
                .param("topic", "question").param("message", "Is it fresh?"));
        Enquiry e = enquiries.findAll().get(0);
        org.awaitility.Awaitility.await().until(() -> EnquiryStatus.DRAFTED.equals(enquiries.findById(e.id).orElseThrow().status));

        org.mockito.Mockito.doReturn(mapper.readTree("{\"source_language\":\"hi\",\"results\":{}}")).when(agent).translateTo(any());
        mvc.perform(post("/seller/messages/" + e.id + "/send").param("reply", "हाँ, आज का बना है").with(host(MAIN)).with(csrf()).with(as(meena)))
                .andExpect(flash().attribute("error", containsString("अनुवाद नहीं हो सका")));
        assertThat(enquiries.findById(e.id).orElseThrow().status).isEqualTo(EnquiryStatus.DRAFTED);
        mvc.perform(get("/account/messages").with(host(MAIN)).with(as(ravi)))
                .andExpect(content().string(not(containsString("आज का बना है"))));
    }

    @Test
    void visitorsChooseTheirLanguageButTheSuperadminStaysInEnglish() throws Exception {
        mvc.perform(get("/").with(host(MAIN))).andExpect(content().string(containsString("Choose your language")));
        mvc.perform(get("/").with(host(MAIN)).header("Accept-Language", "ta-IN,ta;q=0.9"))
                .andExpect(content().string(containsString("<html lang=\"ta\" dir=\"ltr\">")));
        mvc.perform(get("/").with(host(MAIN)).param("lang", "ur"))
                .andExpect(header().string("Set-Cookie", containsString("LAUNCHPAD_LANG=ur")))
                .andExpect(content().string(containsString("<html lang=\"ur\" dir=\"rtl\">")));
        var cookie = new jakarta.servlet.http.Cookie("LAUNCHPAD_LANG", "ur");
        mvc.perform(get("/").with(host(MAIN)).cookie(cookie))
                .andExpect(content().string(containsString("dir=\"rtl\"")))
                .andExpect(content().string(not(containsString("lang-banner"))));
        mvc.perform(get("/admin").with(host(MAIN)).with(superadmin()).cookie(cookie).param("lang", "hi"))
                .andExpect(content().string(containsString("<html lang=\"en\"")));
    }

    @Test
    void loggedInCustomerGetsSellerReplyInTheirAccount() throws Exception {
        Seller s = seller("meena", SellerStatus.PUBLISHED, "x");
        var meena = sellerLogin(s, "9000000001");
        var ravi = accountService.registerCustomer("Ravi", "9123456780", "secret123", null);
        mvc.perform(post("/shops/meena/message").with(host(MAIN)).with(csrf()).with(as(ravi))
                        .param("topic", "discount").param("message", "Any discount for 5 kg?"))
                .andExpect(redirectedUrl("/account/messages"));
        Enquiry e = enquiries.findAll().get(0);
        assertThat(e.customerUserId).isEqualTo(ravi.id);
        assertThat(e.topic).isEqualTo("discount");
        org.awaitility.Awaitility.await().until(() -> EnquiryStatus.DRAFTED.equals(enquiries.findById(e.id).orElseThrow().status));

        // Ravi wrote in English; Meena reads it, and her page, in Hindi.
        org.awaitility.Awaitility.await().until(() -> enquiries.findById(e.id).orElseThrow().translations != null);
        mvc.perform(get("/seller/messages").with(host(MAIN)).with(as(meena)))
                .andExpect(content().string(containsString("छूट माँगी है")))
                .andExpect(content().string(containsString("[hi] Any discount for 5 kg?")))
                .andExpect(content().string(containsString("Namaste Ravi")));
        Seller other = seller("other", SellerStatus.PUBLISHED, "x");
        mvc.perform(post("/seller/messages/" + e.id + "/send").param("reply", "hijack").with(host(MAIN)).with(csrf()).with(as(sellerLogin(other, "9000000003"))))
                .andExpect(flash().attribute("error", "संदेश नहीं मिला।"));
        mvc.perform(post("/seller/messages/" + e.id + "/send").param("reply", "10% off for 5 kg!").with(host(MAIN)).with(csrf()).with(as(meena)))
                .andExpect(flash().attribute("notice", "जवाब भेज दिया गया।"));
        // ...and Ravi gets the reply in English.
        mvc.perform(get("/account/messages").with(host(MAIN)).with(as(ravi)))
                .andExpect(content().string(containsString("[en] 10% off for 5 kg!")));
    }

    @Test
    void superadminGivesSellerALogin() throws Exception {
        Seller s = seller("meena", SellerStatus.PUBLISHED, "x");
        var result = mvc.perform(post("/admin/sellers/" + s.id + "/login").param("phone", "98111 11111")
                        .with(host(MAIN)).with(csrf()).with(superadmin()))
                .andExpect(flash().attribute("notice", containsString("Password: "))).andReturn();
        String notice = (String) result.getFlashMap().get("notice");
        String password = notice.replaceAll("(?s).*Password: (\\S+).*", "$1");
        mvc.perform(post("/login").with(host(MAIN)).with(csrf()).param("login", "9811111111").param("password", password))
                .andExpect(redirectedUrl("/seller"));
        mvc.perform(get("/admin/users").with(host(MAIN)).with(superadmin()))
                .andExpect(content().string(containsString("9811111111")));
    }

    @Test
    void intakeIsInTheSellersLanguageAndTranslatedForTheSuperadmin() throws Exception {
        mvc.perform(get("/join").param("lang", "hi").with(host(MAIN)))
                .andExpect(content().string(containsString("हमारे साथ बेचें")));
        mvc.perform(get("/register/seller").param("lang", "ta").with(host(MAIN)))
                .andExpect(content().string(containsString("உங்கள் கடையைப் பதிவு செய்யுங்கள்")));
        mvc.perform(get("/join").param("lang", "ur").with(host(MAIN)))
                .andExpect(content().string(containsString("dir=\"rtl\"")));

        when(agent.translate(any())).thenReturn(mapper.readTree(
                "{\"source_language\":\"hi\",\"translations\":[{\"field\":\"craft\",\"english\":\"Mango pickle\"}]}"));
        mvc.perform(post("/join").with(host(MAIN)).param("name", "Sunita").param("phone", "9123456780")
                        .param("language", "hi").param("craft", "आम का अचार"))
                .andExpect(redirectedUrl("/join?sent=1"));
        long id = groupRequests.findAll().get(0).id;
        org.awaitility.Awaitility.await().until(() -> groupRequests.findById(id).orElseThrow().translationEn != null);
        mvc.perform(get("/admin/requests").with(host(MAIN)).with(superadmin()))
                .andExpect(content().string(containsString("आम का अचार")))
                .andExpect(content().string(containsString("Mango pickle")));
    }

    @Test
    void repeatedWrongPasswordsLockLogin() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/login").with(host(MAIN)).with(csrf()).param("login", "admin@example.test").param("password", "wrong"))
                    .andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(post("/login").with(host(MAIN)).with(csrf()).param("login", "admin@example.test").param("password", "correct-horse-battery"))
                .andExpect(redirectedUrl("/login?locked"));
    }

    @Test
    void everyPageRendersForItsRole() throws Exception {
        Seller s = seller("meena", SellerStatus.PUBLISHED, "x");
        Product p = products.findBySellerIdOrderBySortOrderAscIdAsc(s.id).get(0);
        var meena = sellerLogin(s, "9000000001");
        var ravi = accountService.registerCustomer("Ravi", "9000000004", "secret123", null);
        var session = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/cart/add").session(session).with(host(MAIN)).with(csrf()).param("productId", p.id.toString()));
        mvc.perform(post("/checkout/" + s.id).session(session).with(host(MAIN)).with(csrf()).with(as(ravi))
                .param("name", "Ravi").param("phone", "9000000004").param("delivery", "pickup").param("payment", "cod"));
        long orderId = orderRepo.findByCustomerIdOrderByCreatedAtDesc(ravi.id).get(0).id;

        String[] publicPages = {"/", "/products", "/p/" + p.id, "/shops/meena/message?product=" + p.id, "/cart",
                "/join", "/register", "/register/seller", "/login"};
        for (String url : publicPages) {
            mvc.perform(get(url).with(host(MAIN))).andExpect(status().isOk());
        }
        String[] customerPages = {"/account", "/account/orders", "/account/orders/" + orderId, "/account/messages", "/register/seller"};
        for (String url : customerPages) {
            mvc.perform(get(url).with(host(MAIN)).with(as(ravi))).andExpect(status().isOk());
        }
        mvc.perform(post("/cart/add").session(session).with(host(MAIN)).with(csrf()).param("productId", p.id.toString()));
        mvc.perform(get("/checkout/" + s.id).session(session).with(host(MAIN)).with(as(ravi))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Place order")));
        String[] sellerPages = {"/seller", "/seller/shop", "/seller/products", "/seller/products/new", "/seller/products/" + p.id,
                "/seller/orders", "/seller/orders/" + orderId, "/seller/messages", "/seller/assistant"};
        for (String url : sellerPages) {
            mvc.perform(get(url).with(host(MAIN)).with(as(meena))).andExpect(status().isOk());
        }
        String[] adminPages = {"/admin", "/admin/orders", "/admin/users", "/admin/requests", "/admin/inbox", "/admin/sellers/" + s.id};
        for (String url : adminPages) {
            mvc.perform(get(url).with(host(MAIN)).with(superadmin())).andExpect(status().isOk());
        }
        mvc.perform(get("/p/999999").with(host(MAIN))).andExpect(status().isNotFound());
    }
}
