package com.collectivelaunchpad.account;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import com.collectivelaunchpad.domain.Account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Logs an account in for this browser session (after signing up, or signing in with Google). */
@Component
public class SessionLogin {

    private final AccountService accounts;
    private final SecurityContextRepository contextRepo = new HttpSessionSecurityContextRepository();

    public SessionLogin(AccountService accounts) {
        this.accounts = accounts;
    }

    public AppUser logIn(Account a, HttpServletRequest req, HttpServletResponse res) {
        AppUser user = AppUser.of(a);
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        if (req.getSession(false) != null) {
            req.changeSessionId();
        }
        contextRepo.saveContext(ctx, req, res);
        accounts.recordLogin(a.id);
        return user;
    }
}
