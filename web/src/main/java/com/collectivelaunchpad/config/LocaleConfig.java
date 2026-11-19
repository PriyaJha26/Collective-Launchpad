package com.collectivelaunchpad.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import com.collectivelaunchpad.domain.Repositories.AccountRepository;
import com.collectivelaunchpad.i18n.LanguageResolver;

/**
 * Every page except the superadmin's is translated into 12 Indian languages
 * (src/main/resources/i18n). ?lang=hi on any page switches; see LanguageResolver for how the
 * choice is remembered.
 */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

    @Bean
    public LanguageResolver localeResolver(SiteUrls urls, AccountRepository accounts) {
        return new LanguageResolver(urls, accounts);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor i = new LocaleChangeInterceptor();
        i.setParamName("lang");
        i.setIgnoreInvalidLocale(true);
        registry.addInterceptor(i).addPathPatterns("/**").excludePathPatterns("/admin", "/admin/**", "/internal/**");
    }
}
