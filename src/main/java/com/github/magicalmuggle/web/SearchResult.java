package com.github.magicalmuggle.web;

import java.util.ArrayList;
import java.util.List;

// 一页搜索结果 + 命中总数。
public final class SearchResult {
    private final List<SearchHit> hits;
    private final long total;

    public SearchResult(List<SearchHit> hits, long total) {
        this.hits = new ArrayList<>(hits);
        this.total = total;
    }

    public List<SearchHit> getHits() {
        return new ArrayList<>(hits);
    }

    public long getTotal() {
        return total;
    }
}
