package com.github.magicalmuggle;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class JdbcCrawlerDao implements CrawlerDao {

    private final Connection connection;

    @SuppressFBWarnings("DMI_CONSTANT_DB_PASSWORD")
    public JdbcCrawlerDao() {
        try {
            String userName = "root";
            String password = "root";
            connection = DriverManager.getConnection(
                    "jdbc:mysql://localhost:3306/news?characterEncoding=utf8", userName, password);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String getNextLink() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select link from links_to_be_processed limit 1"); ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
        }
        return null;
    }

    @Override
    public synchronized String getNextLinkThenDelete() throws SQLException {
        String link = getNextLink();
        if (link != null) {
            updateDatabase(link, "delete from links_to_be_processed where LINK = ?");
        }
        return link;
    }

    @Override
    public void insertNewsIntoDatabase(String url, String title, String content) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into news (url, title, content, created_at, MODIFIED_AT) values (?, ?, ?, now(), now())")) {
            statement.setString(1, url);
            statement.setString(2, title);
            statement.setString(3, content);
            statement.executeUpdate();
        }
    }

    @Override
    public boolean isLinkProcessed(String link) throws SQLException {
        ResultSet resultSet = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "select link from links_already_processed where link = ?")) {
            statement.setString(1, link);
            resultSet = statement.executeQuery();
            if (resultSet.next()) {
                return true;
            }
        } finally {
            if (resultSet != null) {
                resultSet.close();
            }
        }
        return false;
    }

    public void updateDatabase(String link, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, link);
            statement.executeUpdate();
        }
    }

    @Override
    public void insertLinkToBeProcessed(String link) throws SQLException {
        updateDatabase(link, "insert into links_to_be_processed (link) values (?)");
    }

    @Override
    public void insertLinkAlreadyProcessed(String link) throws SQLException {
        updateDatabase(link, "insert into links_already_processed (link) values (?)");
    }
}
