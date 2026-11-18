package com.collectivelaunchpad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LaunchpadApplication {

    public static void main(String[] args) {
        // The local live check connects to 127.0.0.1 and sets the Host header to "<slug>.localhost",
        // because the JVM does not resolve *.localhost the way browsers do.
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        SpringApplication.run(LaunchpadApplication.class, args);
    }
}
