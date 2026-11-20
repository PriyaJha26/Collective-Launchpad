package com.collectivelaunchpad.admin;

import java.security.SecureRandom;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.collectivelaunchpad.config.AppProperties;
import com.collectivelaunchpad.domain.Account;
import com.collectivelaunchpad.domain.Entities.SellerStatus;
import com.collectivelaunchpad.domain.Group;
import com.collectivelaunchpad.domain.Product;
import com.collectivelaunchpad.domain.Repositories.AccountRepository;
import com.collectivelaunchpad.domain.Repositories.GroupRepository;
import com.collectivelaunchpad.domain.Repositories.ProductRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;

/** First start: create the group and its first admin from settings, and optionally a sample shop. */
@Component
public class Bootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    private final GroupRepository groups;
    private final AccountRepository accounts;
    private final SellerRepository sellers;
    private final ProductRepository products;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public Bootstrap(GroupRepository groups, AccountRepository accounts, SellerRepository sellers, ProductRepository products,
                     PasswordEncoder encoder, AppProperties props) {
        this.groups = groups;
        this.accounts = accounts;
        this.sellers = sellers;
        this.products = products;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var seed = props.seed();
        Group group = groups.findFirstByOrderByIdAsc().orElseGet(() -> {
            Group g = new Group();
            g.name = seed.groupName();
            g.tagline = seed.groupTagline();
            g.description = seed.groupDescription();
            g.location = seed.groupLocation();
            g.contactEmail = props.mailFrom();
            log.info("Created group \"{}\"", g.name);
            return groups.save(g);
        });

        if (accounts.findAll().stream().noneMatch(Account::isSuperadmin)) {
            String email = seed.adminEmail();
            String password = seed.adminPassword();
            if (password == null || password.isBlank()) {
                password = randomPassword();
                log.warn("ADMIN_PASSWORD is not set. Created superadmin {} with this one-time generated password: {}"
                        + "  (set ADMIN_PASSWORD before first start to choose your own)", email, password);
            } else if (password.length() < 10) {
                throw new IllegalStateException("ADMIN_PASSWORD must be at least 10 characters.");
            }
            Account a = new Account();
            a.role = Account.SUPERADMIN;
            a.name = "Superadmin";
            a.email = email.strip().toLowerCase();
            a.phone = com.collectivelaunchpad.account.AccountService.normalizePhone(seed.adminPhone()).orElse(null);
            a.passwordHash = encoder.encode(password);
            accounts.save(a);
            log.info("Created superadmin {}", a.email);
        }

        if (seed.sampleShop()) {
            createSampleShops(group);
        }
    }

    private record Sample(String slug, String seller, String shop, String bio, List<Object[]> items) {}

    private static final List<Sample> SAMPLES = List.of(
        new Sample("sample", "Sample Seller", "Sample Pickles & Papad",
            "This is a sample shop that shows what a seller's page looks like. Real shops are added from photos and notes.",
            List.of(new Object[] {"Raw mango pickle", "Spicy, tangy pickle made with raw mangoes.", 25000L, "per 500 g jar"},
                    new Object[] {"Lemon pickle", "Sweet and sour lemon pickle.", 22000L, "per 500 g jar"},
                    new Object[] {"Urad papad", "Hand-rolled papad, sun dried.", null, "per 200 g"})),
        new Sample("weaves", "Sample Weaver", "Sample Handloom Weaves",
            "A sample shop of handwoven cotton goods, to show how a weaver's page can look.",
            List.of(new Object[] {"Cotton saree", "Soft handloom cotton saree with a woven border.", 185000L, "each"},
                    new Object[] {"Cotton dupatta", "Light handwoven dupatta in natural dyes.", 45000L, "each"},
                    new Object[] {"Table runner", "Handwoven table runner, 6 ft.", 32000L, "each"})),
        new Sample("clay", "Sample Potter", "Sample Clay & Pottery",
            "A sample shop of hand-thrown pottery, to show how a potter's page can look.",
            List.of(new Object[] {"Clay water pot", "Cools water naturally. Hand thrown.", 40000L, "each"},
                    new Object[] {"Tea cup set", "Set of 4 small stoneware cups.", 52000L, "per set of 4"},
                    new Object[] {"Diya pack", "Plain terracotta diyas.", 15000L, "per 12"})),
        new Sample("bakes", "Sample Baker", "Sample Home Bakes",
            "A sample shop of home-baked goods, to show how a baker's page can look.",
            List.of(new Object[] {"Whole wheat cookies", "Jaggery sweetened cookies, no maida.", 28000L, "per 250 g box"},
                    new Object[] {"Banana walnut cake", "Moist loaf cake, baked to order.", 45000L, "per loaf"},
                    new Object[] {"Savoury khakhra", "Crisp roasted khakhra, mixed flavours.", null, "per 200 g"})));

    /** Creates any sample shop that does not exist yet, so older installs also get the new ones. */
    private void createSampleShops(Group group) {
        for (Sample sm : SAMPLES) {
            if (sellers.findBySlug(sm.slug()).isPresent()) {
                continue;
            }
            Seller s = new Seller();
            s.groupId = group.id;
            s.slug = sm.slug();
            s.name = sm.seller();
            s.shopName = sm.shop();
            s.bio = sm.bio();
            s.location = group.location;
            s.status = SellerStatus.PUBLISHED;
            s.verifiedAt = java.time.OffsetDateTime.now();
            sellers.save(s);
            int order = 0;
            for (var item : sm.items()) {
                Product p = new Product();
                p.sellerId = s.id;
                p.name = (String) item[0];
                p.description = (String) item[1];
                p.pricePaise = (Long) item[2];
                p.unit = (String) item[3];
                p.sortOrder = order++;
                products.save(p);
            }
            log.info("Created sample shop at {}.{}", sm.slug(), props.site().domain());
        }
    }

    private static String randomPassword() {
        String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
