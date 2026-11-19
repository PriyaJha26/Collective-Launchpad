package com.collectivelaunchpad.i18n;

import org.springframework.stereotype.Component;

import com.collectivelaunchpad.domain.Repositories.AccountRepository;
import com.collectivelaunchpad.domain.Seller;

/** The language a seller reads and writes in: their login's choice, else the shop's language. */
@Component
public class SellerLanguages {

    private final AccountRepository accounts;

    public SellerLanguages(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public String of(Seller s) {
        String own = accounts.findBySellerId(s.id).map(a -> a.language).filter(Languages::isSupported).orElse(null);
        return own != null ? own : Languages.orEnglish(s.language);
    }
}
