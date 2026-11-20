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

        if (seed.sampleShop() && sellers.count() == 0) {
            createSampleShop(group);
        }
    }

    private void createSampleShop(Group group) {
        Seller s = new Seller();
        s.groupId = group.id;
        s.slug = "sample";
        s.name = "Sample Seller";
        s.shopName = "Sample Pickles & Papad";
        s.bio = "This is a sample shop that shows what a seller's page looks like. Real shops are added from photos and notes.";
        s.location = group.location;
        s.status = SellerStatus.PUBLISHED;
        s.verifiedAt = java.time.OffsetDateTime.now();
        sellers.save(s);
        int order = 0;
        for (var item : List.of(
                new Object[] {"Raw mango pickle", "Spicy, tangy pickle made with raw mangoes.", 25000L, "per 500 g jar"},
                new Object[] {"Lemon pickle", "Sweet and sour lemon pickle.", 22000L, "per 500 g jar"},
                new Object[] {"Urad papad", "Hand-rolled papad, sun dried.", null, "per 200 g"})) {
            Product p = new Product();
            p.sellerId = s.id;
            p.name = (String) item[0];
            p.description = (String) item[1];
            p.pricePaise = (Long) item[2];
            p.unit = (String) item[3];
            p.sortOrder = order++;
            products.save(p);
        }
        log.info("Created sample shop at sample.{}", props.site().domain());
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
