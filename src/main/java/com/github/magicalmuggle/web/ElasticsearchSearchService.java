package com.github.magicalmuggle.web;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.github.magicalmuggle.ElasticsearchUtil;
import com.github.magicalmuggle.News;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// 真实搜索：对 news 索引的 title 做 match 查询，行为和 ElasticsearchEngine 保持一致。
// 客户端懒加载并复用——不能像 CLI 那样每次查询都新建 transport。
public final class ElasticsearchSearchService implements SearchService {
    private ElasticsearchClient client;

    @Override
    public SearchResult search(String keyword, int from, int size) throws IOException {
        SearchResponse<News> response = client().search(request -> request
                .index("news")
                .from(from)
                .size(size)
                .query(query -> query.match(match -> match.field("title").query(keyword))), News.class);

        List<SearchHit> hits = new ArrayList<>();
        for (Hit<News> hit : response.hits().hits()) {
            News source = hit.source();
            hits.add(new SearchHit(hit.id(),
                    source == null ? "" : source.getTitle(),
                    source == null ? "" : source.getUrl(),
                    source == null ? "" : source.getContent(),
                    hit.score() == null ? 0.0d : hit.score()));
        }
        long total = response.hits().total() == null ? hits.size() : response.hits().total().value();
        return new SearchResult(hits, total);
    }

    @Override
    public void close() throws IOException {
        if (client != null) {
            client._transport().close();
        }
    }

    private synchronized ElasticsearchClient client() {
        if (client == null) {
            client = ElasticsearchUtil.getElasticsearchClient();
        }
        return client;
    }
}
