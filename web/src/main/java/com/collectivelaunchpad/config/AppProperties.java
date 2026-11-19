package com.collectivelaunchpad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("launchpad")
public record AppProperties(
        Site site,
        String uploadsDir,
        String internalToken,
        String agentUrl,
        String mailFrom,
        Seed seed,
        Costs costs) {

    /**
     * Where pages are served. Production: domain=ourvillagecrafts.in, scheme=https, port=0.
     * Local: domain=localhost, scheme=http, port=8080 (shops at http://meena.localhost:8080).
     */
    public record Site(String domain, String scheme, int port) {}

    /** Used once, on first start, to create the group and its first admin. */
    public record Seed(String groupName, String groupTagline, String groupDescription, String groupLocation,
                       String adminEmail, String adminPhone, String adminPassword, boolean sampleShop) {}

    /** What the group pays each month, for the cost-per-seller figure on the dashboard (INR). */
    public record Costs(double domainPerYear, double emailPerMonth, double hostingPerMonth) {
        public double monthlyTotal() {
            return domainPerYear / 12.0 + emailPerMonth + hostingPerMonth;
        }
    }
}
