package com.collectivelaunchpad.account;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.collectivelaunchpad.domain.Account;
import com.collectivelaunchpad.domain.Group;
import com.collectivelaunchpad.domain.Repositories.AccountRepository;
import com.collectivelaunchpad.domain.Repositories.SellerRepository;
import com.collectivelaunchpad.domain.Seller;
import com.collectivelaunchpad.enquiry.EnquiryService;
import com.collectivelaunchpad.i18n.Msg;
import com.collectivelaunchpad.onboarding.OnboardingService;

/** Registration, login lookup and password management for all three roles. */
@Service
public class AccountService implements UserDetailsService {

    public static final int MIN_PASSWORD = 8;

    public static class AccountException extends RuntimeException {
        public AccountException(String message) {
            super(message);
        }
    }

    public record SellerSignup(String name, String phone, String password, String language, String shopName,
                               String craft, String location, String whatsapp) {}

    private final AccountRepository accounts;
    private final SellerRepository sellers;
    private final PasswordEncoder encoder;
    private final Msg msg;

    public AccountService(AccountRepository accounts, SellerRepository sellers, PasswordEncoder encoder, Msg msg) {
        this.accounts = accounts;
        this.sellers = sellers;
        this.encoder = encoder;
        this.msg = msg;
    }

    // ------------------------------------------------------------------ login

    /** Phone number (any common format) or email. */
    @Override
    public AppUser loadUserByUsername(String identifier) {
        return findByLogin(identifier).map(AppUser::of)
                .orElseThrow(() -> new UsernameNotFoundException("No account for " + identifier));
    }

    public Optional<Account> findByLogin(String identifier) {
        if (identifier == null) {
            return Optional.empty();
        }
        String id = identifier.strip();
        if (id.contains("@")) {
            return accounts.findByEmailIgnoreCase(id);
        }
        return normalizePhone(id).flatMap(accounts::findByPhone);
    }

    public void recordLogin(long accountId) {
        accounts.findById(accountId).ifPresent(a -> {
            a.lastLoginAt = OffsetDateTime.now();
            accounts.save(a);
        });
    }

    /** "+91 98765-43210", "098765 43210", "9876543210" -> "9876543210". Indian mobile numbers only. */
    public static Optional<String> normalizePhone(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 12 && d.startsWith("91")) {
            d = d.substring(2);
        } else if (d.length() == 11 && d.startsWith("0")) {
            d = d.substring(1);
        }
        return d.matches("[6-9]\\d{9}") ? Optional.of(d) : Optional.empty();
    }

    // ------------------------------------------------------------------ registration

    @Transactional
    public Account registerCustomer(String name, String phone, String password, String email) {
        Account a = newAccount(Account.CUSTOMER, name, phone, password, email);
        return accounts.save(a);
    }

    /**
     * A seller registers on the website. Creates their account and a draft shop, which stays
     * hidden until the superadmin approves it. A logged-in customer becomes a seller instead.
     */
    @Transactional
    public Account registerSeller(Group group, SellerSignup in, AppUser current) {
        Account account;
        if (current != null) {
            account = accounts.findById(current.id()).orElseThrow();
            if (account.sellerId != null) {
                throw new AccountException(msg.get("err.alreadyShop"));
            }
            if (account.isSuperadmin()) {
                throw new AccountException(msg.get("err.superadminShop"));
            }
        } else {
            account = newAccount(Account.SELLER, in.name(), in.phone(), in.password(), null);
        }
        String shopName = clip(in.shopName(), 80);
        String craft = clip(in.craft(), 300);
        if (craft == null) {
            throw new AccountException(msg.get("err.craftNeeded"));
        }
        Seller s = new Seller();
        s.groupId = group.id;
        s.name = account.name;
        s.shopName = shopName;
        s.bio = craft;
        s.location = clip(in.location(), 120);
        s.language = OnboardingService.LANGUAGES.containsKey(in.language()) ? in.language() : "hi";
        String wa = clip(in.whatsapp(), 20);
        s.whatsapp = wa != null ? wa : account.phone;
        s.registeredBy = "self";
        sellers.save(s);

        account.role = Account.SELLER;
        account.sellerId = s.id;
        account.language = s.language;
        return accounts.save(account);
    }

    /** The superadmin gives a seller (added by them) a login. Returns the password to hand over. */
    @Transactional
    public String createSellerLogin(long sellerId, String phone, String password) {
        Seller s = sellers.findById(sellerId).orElseThrow(() -> new AccountException("Seller not found."));
        if (accounts.findBySellerId(sellerId).isPresent()) {
            throw new AccountException("This seller already has a login. Reset the password instead.");
        }
        String pw = password == null || password.isBlank() ? generatePassword() : password;
        Account a = newAccount(Account.SELLER, s.name, phone, pw, null);
        a.sellerId = s.id;
        a.language = s.language;
        accounts.save(a);
        return pw;
    }

    @Transactional
    public String resetPassword(long accountId) {
        Account a = accounts.findById(accountId).orElseThrow(() -> new AccountException("Account not found."));
        String pw = generatePassword();
        a.passwordHash = encoder.encode(pw);
        accounts.save(a);
        return pw;
    }

    @Transactional
    public void changePassword(long accountId, String current, String next) {
        Account a = accounts.findById(accountId).orElseThrow();
        if (current == null || !encoder.matches(current, a.passwordHash)) {
            throw new AccountException(msg.get("err.wrongPassword"));
        }
        checkPassword(next);
        a.passwordHash = encoder.encode(next);
        accounts.save(a);
    }

    @Transactional
    public void setActive(long accountId, boolean active, long byAccountId) {
        if (accountId == byAccountId && !active) {
            throw new AccountException("You cannot switch off your own account.");
        }
        Account a = accounts.findById(accountId).orElseThrow(() -> new AccountException("Account not found."));
        a.active = active;
        accounts.save(a);
    }

    @Transactional
    public void updateProfile(long accountId, String name, String email) {
        Account a = accounts.findById(accountId).orElseThrow();
        String n = clip(name, 80);
        if (n == null) {
            throw new AccountException(msg.get("err.enterName"));
        }
        a.name = n;
        a.email = cleanEmail(email, a.id);
        accounts.save(a);
    }

    // ------------------------------------------------------------------ helpers

    private Account newAccount(String role, String name, String phone, String password, String email) {
        String n = clip(name, 80);
        if (n == null) {
            throw new AccountException(msg.get("err.enterName"));
        }
        String p = normalizePhone(phone).orElseThrow(() ->
                new AccountException(msg.get("err.mobileIndian")));
        if (accounts.existsByPhone(p)) {
            throw new AccountException(msg.get("err.mobileTaken"));
        }
        checkPassword(password);
        Account a = new Account();
        a.role = role;
        a.name = n;
        a.phone = p;
        a.email = cleanEmail(email, null);
        a.passwordHash = encoder.encode(password);
        return a;
    }

    private String cleanEmail(String email, Long selfId) {
        String e = clip(email, 200);
        if (e == null) {
            return null;
        }
        e = e.toLowerCase(Locale.ROOT);
        if (!EnquiryService.EMAIL.matcher(e).matches()) {
            throw new AccountException(msg.get("err.email"));
        }
        var existing = accounts.findByEmailIgnoreCase(e);
        if (existing.isPresent() && !existing.get().id.equals(selfId)) {
            throw new AccountException(msg.get("err.emailTaken"));
        }
        return e;
    }

    private void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new AccountException(msg.get("err.passwordShort", MIN_PASSWORD));
        }
        if (password.length() > 200) {
            throw new AccountException(msg.get("err.passwordLong"));
        }
    }

    public static String generatePassword() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
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
