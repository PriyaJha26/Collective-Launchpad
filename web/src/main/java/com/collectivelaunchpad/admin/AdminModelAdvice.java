package com.collectivelaunchpad.admin;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.collectivelaunchpad.config.SiteUrls;
import com.collectivelaunchpad.domain.Group;
import com.collectivelaunchpad.domain.Repositories.GroupRepository;

/** Shared model attributes and form binding for the admin pages. */
@ControllerAdvice(assignableTypes = {LoginController.class, DashboardController.class, OnboardingController.class,
        SellerAdminController.class, InboxController.class})
public class AdminModelAdvice {

    private final GroupRepository groups;
    private final SiteUrls urls;

    public AdminModelAdvice(GroupRepository groups, SiteUrls urls) {
        this.groups = groups;
        this.urls = urls;
    }

    @ModelAttribute("group")
    Group group() {
        return groups.findFirstByOrderByIdAsc().orElseThrow();
    }

    @ModelAttribute("urls")
    SiteUrls urls() {
        return urls;
    }

    @InitBinder
    void binder(WebDataBinder binder) {
        binder.initDirectFieldAccess(); // form objects use public fields
        binder.setAutoGrowCollectionLimit(100);
    }
}
