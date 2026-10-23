package com.github.magicalmuggle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkPolicyTest {

    @Test
    void acceptsAPlainSinaPage() {
        LinkPolicy.Decision decision = LinkPolicy.check("https://news.sina.cn/2026-10-20/detail.html");

        assertTrue(decision.isAccepted());
        assertTrue(decision.getReason().contains("sina.cn"));
    }

    @Test
    void rejectsLinksOutsideSina() {
        LinkPolicy.Decision decision = LinkPolicy.check("https://example.com/news");

        assertFalse(decision.isAccepted());
        assertTrue(decision.getReason().contains("sina.cn"));
    }

    @Test
    void rejectsExcludedSubdomains() {
        assertFalse(LinkPolicy.check("https://edu.sina.cn/a").isAccepted());
        assertFalse(LinkPolicy.check("https://auto.sina.cn/a").isAccepted());
        assertFalse(LinkPolicy.check("https://travel.sina.cn/a").isAccepted());
        assertTrue(LinkPolicy.check("https://edu.sina.cn/a").getReason().contains("edu.sina.cn"));
    }

    @Test
    void rejectsJavascriptAndPhpLinks() {
        assertFalse(LinkPolicy.check("javascript:void(0)").isAccepted());
        assertFalse(LinkPolicy.check("https://sina.cn/redirect.php").isAccepted());
        assertFalse(LinkPolicy.check("").isAccepted());
        assertFalse(LinkPolicy.check(null).isAccepted());
    }
}
