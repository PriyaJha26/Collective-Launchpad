package com.collectivelaunchpad.admin;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.collectivelaunchpad.account.AppUser;
import com.collectivelaunchpad.config.SiteUrls;
import com.collectivelaunchpad.domain.Group;
import com.collectivelaunchpad.domain.Repositories.GroupRepository;
import com.collectivelaunchpad.i18n.LangLinks;
import com.collectivelaunchpad.i18n.LanguageResolver;
import com.collectivelaunchpad.i18n.Languages;
import com.collectivelaunchpad.i18n.Msg;
import com.collectivelaunchpad.shop.Cart;

import jakarta.servlet.http.HttpServletRequest;

/** Shared model attributes and form binding for every page (not the internal JSON API). */
@ControllerAdvice(basePackages = {"com.collectivelaunchpad.admin", "com.collectivelaunchpad.site",
        "com.collectivelaunchpad.shop", "com.collectivelaunchpad.account", "com.collectivelaunchpad.seller"})
public class AdminModelAdvice {

    private final GroupRepository groups;
    private final SiteUrls urls;
    private final LanguageResolver languages;

    public AdminModelAdvice(GroupRepository groups, SiteUrls urls, LanguageResolver languages) {
        this.groups = groups;
        this.urls = urls;
        this.languages = languages;
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

    // ------------------------------------------------------------------ language

    /** The reader's language code, e.g. "hi". */
    @ModelAttribute("lang")
    String lang() {
        return Msg.lang();
    }

    @ModelAttribute("rtl")
    boolean rtl() {
        return Languages.isRtl(Msg.lang());
    }

    /** False until the visitor picks a language: the "Choose your language" banner is shown. */
    @ModelAttribute("langChosen")
    boolean langChosen(HttpServletRequest req) {
        return languages.hasChosen(req);
    }

    @ModelAttribute("siteLanguages")
    java.util.Map<String, String> siteLanguages() {
        return Languages.NATIVE;
    }

    @ModelAttribute("langLinks")
    LangLinks langLinks(HttpServletRequest req) {
        return LangLinks.of(req);
    }

    @InitBinder
    void binder(WebDataBinder binder) {
        binder.initDirectFieldAccess(); // form objects use public fields
        binder.setAutoGrowCollectionLimit(100);
    }
}
