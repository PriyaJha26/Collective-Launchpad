package com.collectivelaunchpad.uploads;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class UploadsWebConfig implements WebMvcConfigurer {

    private final UploadService uploads;

    public UploadsWebConfig(UploadService uploads) {
        this.uploads = uploads;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // File names are random and never reused, so photos can be cached for a long time.
        registry.addResourceHandler("/uploads/sellers/**")
                .addResourceLocations(uploads.root().resolve("sellers").toUri().toString() + "/")
                .setCacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic());
    }
}
