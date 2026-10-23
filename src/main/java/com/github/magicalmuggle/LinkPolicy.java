package com.github.magicalmuggle;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

// 链接过滤规则的唯一实现：Crawler 用它决定是否入队，Web 界面用它解释某个 URL 为什么会被/不会被抓。
public final class LinkPolicy {
    private static final String REQUIRED_HOST = "sina.cn";
    private static final List<String> EXCLUDED_SUBDOMAINS = Collections.unmodifiableList(Arrays.asList(
            "passport.sina.cn", "edu.sina.cn", "auto.sina.cn", "gu.sina.cn",
            "ts.gd.sina.cn", "travel.sina.cn"));

    private LinkPolicy() {
    }

    public static Decision check(String link) {
        if (link == null || link.trim().isEmpty()) {
            return new Decision(false, "链接为空");
        }
        if (!link.contains(REQUIRED_HOST)) {
            return new Decision(false, "不包含 " + REQUIRED_HOST);
        }
        for (String subdomain : EXCLUDED_SUBDOMAINS) {
            if (link.contains(subdomain)) {
                return new Decision(false, "排除了子域名 " + subdomain);
            }
        }
        if (link.toLowerCase(Locale.ROOT).startsWith("javascript")) {
            return new Decision(false, "javascript 链接");
        }
        if (link.contains(".php")) {
            return new Decision(false, "包含 .php");
        }
        return new Decision(true, "包含 " + REQUIRED_HOST + "，且未命中排除规则");
    }

    public static final class Decision {
        private final boolean accepted;
        private final String reason;

        Decision(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String getReason() {
            return reason;
        }
    }
}
