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
        while ((link = dao.getNextLinkThenDelete("links_to_be_processed")) != null) {
            if (dao.isLinkProcessed(link)) {
                continue;
            }

            System.out.println(link);
            Document doc = httpGetAndParseHTML(link);
            parseUrlsFromPageAndStoreIntoDatabase(doc);
            storeIntoDatabaseIfItIsNewsPage(doc, link);
            dao.updateDatabase(link, "insert into links_already_processed (link) values (?)");
        }
    }

    private void parseUrlsFromPageAndStoreIntoDatabase(Document doc) throws SQLException {
        Elements aTags = doc.select("a");
        for (Element aTag : aTags) {
            String link = aTag.attr("href");
            if (isRequiredLink(link)) {
                if (link.startsWith("//")) {
                    link = "https:" + link;
                }
                dao.updateDatabase(link.replaceAll("\\s", ""), "insert into links_to_be_processed (link) values (?)");
            }
        }
    }

    private void storeIntoDatabaseIfItIsNewsPage(Document doc, String link) throws SQLException {
        Elements articleTags = doc.select("article");
        if (!articleTags.isEmpty()) {
            Element articleTag = articleTags.get(0);
            String title = articleTag.child(0).text();
            String content = articleTag.select("p").stream().map(Element::text)
                    .collect(Collectors.joining("\n"));
            if (!title.equals("") && !content.equals("")) {
                dao.insertNewsIntoDatabase(link, title, content);
            }
        }
    }

    private Document httpGetAndParseHTML(String link) throws IOException, ParseException {
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

    private boolean isRequiredLink(String link) {
        return isSinaPageLink(link)
                && isNotExcludedPageLink(link)
                && isNotJavaScriptLink(link)
                && isNotPhpLink(link);
    }

    private boolean isSinaPageLink(String link) {
        return link.contains("sina.cn");
    }

    private boolean isNotExcludedPageLink(String link) {
        return !(link.contains("passport.sina.cn")
                || link.contains("edu.sina.cn")
                || link.contains("auto.sina.cn")
                || link.contains("gu.sina.cn")
                || link.contains("ts.gd.sina.cn")
                || link.contains("travel.sina.cn"));
    }

    private boolean isNotJavaScriptLink(String link) {
        return !link.toLowerCase().startsWith("javascript");
    }

    private boolean isNotPhpLink(String link) {
        return !link.contains(".php");
    }
}
