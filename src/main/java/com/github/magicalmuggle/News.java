package com.github.magicalmuggle;

import java.time.Instant;

public class News {
    private String url;
    private String title;
    private String content;
    private Instant createdAt;
    private Instant modifiedAt;

    public News() {
    }

    public News(News old) {
        this.url = old.url;
        this.title = old.title;
        this.content = old.content;
        this.createdAt = old.createdAt;
        this.modifiedAt = old.modifiedAt;
    }

    public News(String url, String title, String content) {
        this.url = url;
        this.title = title;
        this.content = content;
    }

    public News(String url, String title, String content, Instant createdAt, Instant modifiedAt) {
        this.url = url;
        this.title = title;
        this.content = content;
        this.createdAt = createdAt;
        this.modifiedAt = modifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(Instant modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public static void cutNewsContent(News news, int length) {
        if (news.getContent().length() > length) {
            news.setContent(news.getContent().substring(0, length));
        }
    }

    @Override
    public String toString() {
        return "News{" +
                "url='" + url + '\'' +
                ", title='" + title + '\'' +
                ", content='" + content + '\'' +
                ", createdAt=" + createdAt +
                ", modifiedAt=" + modifiedAt +
                '}';
    }
}
