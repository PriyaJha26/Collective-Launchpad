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
    @Autowired RateLimiter rateLimiter;
    @Autowired ObjectMapper mapper;
    @MockitoBean AgentClient agent;

    static final String MAIN = "example.test";
    static final String TOKEN = "test-token";

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate sellers, products, onboarding_sessions, enquiries, replies, email_log restart identity cascade");
        rateLimiter.reset();
        when(agent.classify(any())).thenReturn(mapper.readTree("{\"category\":\"order\",\"urgency\":\"normal\",\"language\":\"en\"}"));
        when(agent.draftReply(any())).thenReturn(mapper.readTree("{\"reply\":\"Namaste Ravi\",\"check_points\":[\"Confirm Saturday\"]}"));
        when(agent.onboardingTurn(any())).thenReturn(mapper.readTree(
                "{\"status\":\"needs_input\",\"appended\":[],\"pending_tool_results\":[],\"question\":\"What is the price?\"}"));
        when(agent.health()).thenReturn(mapper.readTree("{\"mode\":\"mock\"}"));
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
                .andExpect(content().string(containsString("Our Village Crafts")))
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
                        .param("email", "admin@example.test").param("password", "correct-horse-battery"))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(authenticated().withUsername("admin@example.test"));
        mvc.perform(post("/login").with(host(MAIN)).with(csrf())
                        .param("email", "admin@example.test").param("password", "wrong"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void adminFormsNeedCsrfToken() throws Exception {
        Seller s = seller("meena", SellerStatus.PENDING_REVIEW, "x");
        mvc.perform(post("/admin/sellers/" + s.id + "/publish").with(host(MAIN)).with(user("admin@example.test").roles("ADMIN")))
                .andExpect(status().isForbidden());
        assertThat(sellers.findById(s.id).orElseThrow().status).isEqualTo(SellerStatus.PENDING_REVIEW);

        mvc.perform(post("/admin/sellers/" + s.id + "/publish").with(host(MAIN)).with(csrf()).with(user("admin@example.test").roles("ADMIN")))
                .andExpect(status().isFound());
        Seller published = sellers.findById(s.id).orElseThrow();
        assertThat(published.status).isEqualTo(SellerStatus.PUBLISHED);
        assertThat(published.verifiedAt).isNotNull();
    }

    @Test
    void dashboardAndEditorRender() throws Exception {
        Seller s = seller("meena", SellerStatus.PENDING_REVIEW, "x");
        var admin = user("admin@example.test").roles("ADMIN");
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
        var admin = user("admin@example.test").roles("ADMIN");
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
        var admin = user("admin@example.test").roles("ADMIN");
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
        assertThat(firstText).contains("Seller's name: Meena").contains("Group: Our Village Crafts").contains("Ladoo 300/kg");
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
                        .with(host(MAIN)).with(csrf()).with(user("admin@example.test").roles("ADMIN")))
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
}
