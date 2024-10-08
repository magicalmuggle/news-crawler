package com.github.magicalmuggle;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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

public class Crawler extends Thread {
    private final CrawlerDao dao;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public Crawler(CrawlerDao dao) {
        this.dao = dao;
    }

    @Override
    public void run() {
        try {
            String link;
            while ((link = dao.getNextLinkThenDelete()) != null) {
                if (dao.isLinkProcessed(link)) {
                    continue;
                }
                System.out.println(link);
                Document doc = httpGetAndParseHTML(link);
                parseUrlsFromPageAndStoreIntoDatabase(doc);
                storeIntoDatabaseIfItIsNewsPage(doc, link);
                dao.insertLinkAlreadyProcessed(link);
            }
        } catch (SQLException | IOException | ParseException e) {
            throw new RuntimeException(e);
        }
    }

    public void crawlTheIndexPage() {
        String indexLink = "https://sina.cn/";

        try {
            if (!dao.isLinkProcessed(indexLink)) {
                System.out.println(indexLink);
                Document doc = httpGetAndParseHTML(indexLink);
                parseUrlsFromPageAndStoreIntoDatabase(doc);
                dao.insertLinkAlreadyProcessed(indexLink);
            }
        } catch (IOException | ParseException | SQLException e) {
            throw new RuntimeException(e);
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
                dao.insertLinkToBeProcessed(link.replaceAll("\\s", ""));
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
            if (!title.isEmpty() && !content.isEmpty()) {
                dao.insertNewsIntoDatabase(link, title, content);
            }
        }
    }

    private Document httpGetAndParseHTML(String link) throws IOException, ParseException {
        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(cleanUrl(link));
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

    private String cleanUrl(String url) {
        url = url.replace("{", "");
        url = url.replace("}", "");
        return url;
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
