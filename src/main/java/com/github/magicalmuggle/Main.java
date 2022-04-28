package com.github.magicalmuggle;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Main {
    // 初始链接
    static final String INDEX = "https://sina.cn/";

    public static void main(String[] args) throws IOException, ParseException {
        Set<String> processedLinkPool = new HashSet<>();
        List<String> unprocessedLinkPool = new ArrayList<>();
        unprocessedLinkPool.add(INDEX);

        while (!unprocessedLinkPool.isEmpty()) {
            // 获取一个未处理的链接，并将它从 unprocessedLinkPool 中删除（ArrayList 从尾部删除更有效率）
            String link = unprocessedLinkPool.remove(unprocessedLinkPool.size() - 1);
            // 把缺少协议的链接设为 https
            if (link.startsWith("//")) {
                link = "https:" + link;
            }

            if (processedLinkPool.contains(link)) {
                continue;
            }

            if (isRequiredLink(link)) {
                Document doc = httpGetAndParseHTML(link);
                addNewLinksIntoUnprocessedLinkPool(unprocessedLinkPool, doc);
                storeIntoDatabaseIfItIsNewsPage(doc);
                processedLinkPool.add(link);
            }
        }
    }

    private static void addNewLinksIntoUnprocessedLinkPool(List<String> linkPool, Document doc) {
        doc.select("a").stream().map(aTag -> aTag.attr("href")).forEach(linkPool::add);
    }

    private static void storeIntoDatabaseIfItIsNewsPage(Document doc) {
        Elements articleTags = doc.select("article");
        if (!articleTags.isEmpty()) {
            articleTags.forEach(articleTag -> {
                String title = articleTag.child(0).text();
                System.out.println(title);
            });
        }
    }

    private static Document httpGetAndParseHTML(String link) throws IOException, ParseException {
        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(link);
            httpGet.setHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/100.0.4896.75 Safari/537.36");

            try (CloseableHttpResponse response = httpclient.execute(httpGet)) {
                System.out.println(response.getCode() + " " + response.getReasonPhrase());
                System.out.println(link);
                HttpEntity entity = response.getEntity();
                String html = EntityUtils.toString(entity);
                return Jsoup.parse(html);
            }
        }
    }

    private static boolean isRequiredLink(String link) {
        return isIndexPage(link) || isNewsPage(link);
    }

    private static boolean isIndexPage(String link) {
        return INDEX.equals(link);
    }

    private static boolean isNewsPage(String link) {
        return link.contains("//news.sina.cn");
    }
}
