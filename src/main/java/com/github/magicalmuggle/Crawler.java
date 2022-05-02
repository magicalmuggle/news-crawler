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
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.sql.SQLException;
import java.util.stream.Collectors;

public class Crawler {
    private final CrawlerDao dao = new JdbcCrawlerDao();

    public static void main(String[] args) throws IOException, ParseException, SQLException {
        new Crawler().run();
    }

    public void run() throws IOException, ParseException, SQLException {
        String link;
        while ((link = dao.getNextLinkThenDelete("LINKS_TO_BE_PROCESSED")) != null) {
            if (dao.isLinkProcessed(link)) {
                continue;
            }

            if (isRequiredLink(link)) {
                System.out.println(link);
                Document doc = httpGetAndParseHTML(link);
                parseUrlsFromPageAndStoreIntoDatabase(doc);
                storeIntoDatabaseIfItIsNewsPage(doc, link);
                dao.updateDatabase(link, "insert into LINKS_ALREADY_PROCESSED (link) values (?)");
            }
        }
    }

    private void parseUrlsFromPageAndStoreIntoDatabase(Document doc) throws SQLException {

        Elements aTags = doc.select("a");
        for (Element aTag : aTags) {
            String link = aTag.attr("href");

            if (link.startsWith("//")) {
                link = "https:" + link;
            }

            if (!link.toLowerCase().startsWith("javascript")) {
                dao.updateDatabase(link, "insert into LINKS_TO_BE_PROCESSED (link) values (?)");
            }
        }
    }

    private void storeIntoDatabaseIfItIsNewsPage(Document doc, String link) throws SQLException {
        Elements articleTags = doc.select("article");
        if (!articleTags.isEmpty()) {
            for (Element articleTag : articleTags) {
                String title = articleTag.child(0).text();
                String content = articleTag.select("p").stream().map(Element::text)
                        .collect(Collectors.joining("\n"));
                dao.insertNewsIntoDatabase(link, title, content);
            }
        }
    }

    private static Document httpGetAndParseHTML(String link) throws IOException, ParseException {
        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(link);
            httpGet.setHeader("user-agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/100.0.4896.75 Safari/537.36");

            try (CloseableHttpResponse response = httpclient.execute(httpGet)) {
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
        return "https://sina.cn/".equals(link);
    }

    private static boolean isNewsPage(String link) {
        return link.contains("//news.sina.cn");
    }
}
