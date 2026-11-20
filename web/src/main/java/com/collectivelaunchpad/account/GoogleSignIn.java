package com.collectivelaunchpad.account;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.collectivelaunchpad.domain.Account;
import com.collectivelaunchpad.domain.Repositories.AccountRepository;

/**
 * "Continue with Google" for customers and sellers:
 * an account already linked to this Google id logs in; otherwise an account with the same
 * (Google-verified) email is linked and logs in; otherwise a new customer account is created.
 * The superadmin never signs in with Google.
 */
@Service
public class GoogleSignIn {

    public enum Refusal { EMAIL_NOT_VERIFIED, SUPERADMIN, OTHER_GOOGLE_ACCOUNT }

    public static class Refused extends RuntimeException {
        public final Refusal reason;

        Refused(Refusal reason) {
            super(reason.name());
            this.reason = reason;
        }
    }

    /** What Google tells us about the person (from the verified ID token). */
    public record GoogleUser(String sub, String email, boolean emailVerified, String name) {}

    private final AccountRepository accounts;

    public GoogleSignIn(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public Account signIn(GoogleUser g) {
        if (g.sub() == null || g.sub().isBlank()) {
            throw new IllegalArgumentException("Google returned no account id");
        }
        var linked = accounts.findByGoogleSub(g.sub());
        if (linked.isPresent()) {
            return allowed(linked.get());
        }
        if (!g.emailVerified() || g.email() == null || g.email().isBlank()) {
            throw new Refused(Refusal.EMAIL_NOT_VERIFIED);
        }
        String email = g.email().strip().toLowerCase(Locale.ROOT);
        var sameEmail = accounts.findByEmailIgnoreCase(email);
        if (sameEmail.isPresent()) {
            Account a = allowed(sameEmail.get());
            if (a.googleSub != null) {
                throw new Refused(Refusal.OTHER_GOOGLE_ACCOUNT);
            }
            a.googleSub = g.sub();
            return accounts.save(a);
        }
        Account a = new Account();
        a.role = Account.CUSTOMER;
        a.name = name(g.name(), email);
        a.email = email;
        a.googleSub = g.sub();
        return accounts.save(a);
    }

    private static Account allowed(Account a) {
        if (a.isSuperadmin()) {
            throw new Refused(Refusal.SUPERADMIN);
        }
        return a;
    }

    private static String name(String given, String email) {
        String n = given == null ? "" : given.strip();
        if (n.isEmpty()) {
            n = email.substring(0, email.indexOf('@'));
        }
        return n.length() > 80 ? n.substring(0, 80) : n;
    }
}
