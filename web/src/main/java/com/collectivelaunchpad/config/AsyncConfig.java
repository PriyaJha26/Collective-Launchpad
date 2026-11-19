package com.collectivelaunchpad.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
public class AsyncConfig {

    /** Background work: agent turns, enquiry drafting, emails, live checks. */
    @Bean(destroyMethod = "close")
    public ExecutorService backgroundExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
