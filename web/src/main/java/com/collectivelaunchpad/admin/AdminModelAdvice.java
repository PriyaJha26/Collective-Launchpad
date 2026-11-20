package com.collectivelaunchpad.admin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.collectivelaunchpad.account.AppUser;
import com.collectivelaunchpad.config.SiteUrls;
import com.collectivelaunchpad.domain.Group;
import com.collectivelaunchpad.domain.Repositories.GroupRepository;
import com.collectivelaunchpad.shop.Cart;

import jakarta.servlet.http.HttpServletRequest;

/** Shared model attributes and form binding for every page (not the internal JSON API). */
@ControllerAdvice(basePackages = {"com.collectivelaunchpad.admin", "com.collectivelaunchpad.site",
        "com.collectivelaunchpad.shop", "com.collectivelaunchpad.account", "com.collectivelaunchpad.seller"})
public class AdminModelAdvice {

    private final GroupRepository groups;
    private final SiteUrls urls;
    private final boolean googleLogin;

    public AdminModelAdvice(GroupRepository groups, SiteUrls urls,
                            ObjectProvider<ClientRegistrationRepository> google) {
        this.groups = groups;
        this.urls = urls;
        this.googleLogin = google.getIfAvailable() != null;
    }

    /** Show "Continue with Google" (only when GOOGLE_CLIENT_ID / SECRET are set). */
    @ModelAttribute("googleLogin")
    boolean googleLogin() {
        return googleLogin;
    }

    @ModelAttribute("group")
    Group group() {
        return groups.findFirstByOrderByIdAsc().orElseThrow();
    }

    @ModelAttribute("urls")
    SiteUrls urls() {
        return urls;
    }

    /** The logged-in user, or null. */
    @ModelAttribute("me")
    AppUser me(Authentication auth) {
        return AppUser.from(auth);
    }

    @ModelAttribute("cartCount")
    int cartCount(HttpServletRequest req) {
        return Cart.count(req.getSession(false));
    }

    /**
     * The current path on the main domain (e.g. "/seller/orders"), to highlight where the reader
     * is in the top bar. Empty on shop subdomains, where the bar links back to the main site.
     */
    @ModelAttribute("path")
    String path(HttpServletRequest req) {
        return urls.domain().equalsIgnoreCase(req.getServerName())
                ? req.getRequestURI().substring(req.getContextPath().length()) : "";
    }

    @InitBinder
    void binder(WebDataBinder binder) {
        binder.initDirectFieldAccess(); // form objects use public fields
        binder.setAutoGrowCollectionLimit(100);
    }
}
