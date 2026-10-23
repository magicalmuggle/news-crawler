package com.github.magicalmuggle;

import java.sql.SQLException;

public interface CrawlerDao {
    String getNextLinkThenDelete() throws SQLException;

    void insertNewsIntoDatabase(String url, String title, String content) throws SQLException;

    boolean isLinkProcessed(String link) throws SQLException;

    void insertLinkToBeProcessed(String link) throws SQLException;

    // reason: OK / DEAD / SKIPPED，供界面区分「抓过」和「抓过但已死」
    void insertLinkAlreadyProcessed(String link, String reason) throws SQLException;

    // 被拦 / 网络错误重试耗尽后的链接。不写 links_already_processed，只记录以便手动重试。
    void insertFailedLink(String link, int statusCode, String outcome) throws SQLException;
}
