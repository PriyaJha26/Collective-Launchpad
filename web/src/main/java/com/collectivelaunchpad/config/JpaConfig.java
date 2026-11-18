package com.collectivelaunchpad.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.collectivelaunchpad.domain", considerNestedRepositories = true)
public class JpaConfig {}
