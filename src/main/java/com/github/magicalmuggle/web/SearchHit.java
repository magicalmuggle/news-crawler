package com.github.magicalmuggle.web;

// 一条搜索结果。content 来自 ES 里被裁剪过的正文，只作摘要用。
public final class SearchHit {
    private final String id;
    private final String title;
    private final String url;
    private final String content;
    private final double score;

    public SearchHit(String id, String title, String url, String content, double score) {
        this.id = id;
        this.title = title;
        this.url = url;
        this.content = content;
        this.score = score;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public String getContent() {
        return content;
    }

    public double getScore() {
        return score;
    }
}
