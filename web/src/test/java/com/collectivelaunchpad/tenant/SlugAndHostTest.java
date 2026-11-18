package com.collectivelaunchpad.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.collectivelaunchpad.tenant.HostClassifier.Kind;

class SlugAndHostTest {

    private final HostClassifier hosts = new HostClassifier("ourvillagecrafts.in");

    @Test
    void slugRules() {
        assertTrue(SlugRules.isValid("meena"));
        assertTrue(SlugRules.isValid("meena-devi"));
        assertTrue(SlugRules.isValid("ab"));
        assertTrue(SlugRules.isValid("meena2"));
        assertFalse(SlugRules.isValid("a"), "too short");
        assertFalse(SlugRules.isValid("a".repeat(31)), "too long");
        assertFalse(SlugRules.isValid("2meena"), "must start with a letter");
        assertFalse(SlugRules.isValid("meena-"), "no trailing hyphen");
        assertFalse(SlugRules.isValid("mee--na"));
        assertFalse(SlugRules.isValid("Meena"));
        assertFalse(SlugRules.isValid("मीना"));
        assertFalse(SlugRules.isValid("www"));
        assertFalse(SlugRules.isValid("hello"), "the group mailbox name is reserved");
        assertFalse(SlugRules.isValid("admin"));
        assertEquals("meena-devi", SlugRules.normalize("  Meena Devi "));
    }

    @Test
    void hostClassification() {
        assertEquals(Kind.MAIN, hosts.classify("ourvillagecrafts.in").kind());
        assertEquals(Kind.MAIN, hosts.classify("OurVillageCrafts.IN.").kind());
        assertEquals(Kind.WWW, hosts.classify("www.ourvillagecrafts.in").kind());
        assertEquals(new HostClassifier.Result(Kind.SHOP, "meena"), hosts.classify("meena.ourvillagecrafts.in"));
        assertEquals(Kind.UNKNOWN, hosts.classify("a.b.ourvillagecrafts.in").kind(), "nested");
        assertEquals(Kind.UNKNOWN, hosts.classify("admin.ourvillagecrafts.in").kind(), "reserved");
        assertEquals(Kind.UNKNOWN, hosts.classify("evil-ourvillagecrafts.in").kind(), "foreign");
        assertEquals(Kind.UNKNOWN, hosts.classify("example.com").kind());
        assertEquals(Kind.UNKNOWN, hosts.classify("").kind());
        assertEquals(Kind.UNKNOWN, hosts.classify("1.2.3.4").kind());
    }
}
