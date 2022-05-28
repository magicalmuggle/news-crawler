package com.github.magicalmuggle;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class ElasticsearchEngine {
    public static void main(String[] args) throws IOException {
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                System.out.print("Please input a search keyword: ");

                String keyword = bufferedReader.readLine();

                searchTitle(keyword);
            }
        }

    }

    private static void searchTitle(String keyword) throws IOException {
        ElasticsearchClient client = null;
        try {
            client = ElasticsearchUtils.getElasticsearchClient();

            SearchResponse<News> response = client.search(s -> s.index("news").query(
                    q -> q.match(t -> t.field("content").query(keyword))), News.class);

            response.hits().hits().forEach(x -> System.out.println(x.source()));
        } finally {
            if (client != null) {
                client._transport().close();
            }
        }
    }
}
