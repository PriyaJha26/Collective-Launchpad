package com.collectivelaunchpad.account;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.collectivelaunchpad.domain.Account;

/**
 * The logged-in person. Authorities: superadmins get SUPERADMIN, sellers get SELLER; everyone
 * also gets CUSTOMER, so any account can place orders.
 */
public record AppUser(long id, String role, String name, String login, String passwordHash, Long sellerId, boolean active)
        implements UserDetails {

    public static AppUser of(Account a) {
        return new AppUser(a.id, a.role, a.name, a.login(), a.passwordHash, a.sellerId, a.active);
    }

    public boolean isSuperadmin() {
        return Account.SUPERADMIN.equals(role);
    }

    public boolean isSeller() {
        return Account.SELLER.equals(role) && sellerId != null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> out = new ArrayList<>();
        out.add(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        if (isSuperadmin()) {
            out.add(new SimpleGrantedAuthority("ROLE_SUPERADMIN"));
        }
        if (isSeller()) {
            out.add(new SimpleGrantedAuthority("ROLE_SELLER"));
        }
        return out;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return login;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    /** The current user, or null when not logged in. */
    public static AppUser from(Authentication auth) {
        return auth != null && auth.getPrincipal() instanceof AppUser u ? u : null;
    }
}
