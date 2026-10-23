package com.github.magicalmuggle.web;

// links_already_processed 的一行：链接 + 处理原因（OK / DEAD / SKIPPED）。
public final class ProcessedLink {
    private String link;
    private String reason;

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
