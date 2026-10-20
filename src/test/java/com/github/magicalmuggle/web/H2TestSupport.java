package com.github.magicalmuggle.web;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Properties;

// H2（MySQL 兼容模式）+ 测试专用 MyBatis 配置，让 Web 层的查询可以在不连 MySQL 的情况下验证。
final class H2TestSupport {
    static final String URL =
            "jdbc:h2:mem:news_crawler_admin;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String USERNAME = "sa";
    private static final String PASSWORD = "";

    private H2TestSupport() {
    }

    static SqlSessionFactory buildFactory() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("db.url", URL);
        try (InputStream inputStream = Resources.getResourceAsStream("db/mybatis/config-h2.xml")) {
            return new SqlSessionFactoryBuilder().build(inputStream, properties);
        }
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USERNAME, PASSWORD);
    }

    static void resetSchema() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists news");
            statement.execute("drop table if exists links_to_be_processed");
            statement.execute("drop table if exists links_already_processed");
            statement.execute("create table news ("
                    + "id bigint auto_increment primary key, "
                    + "title text, "
                    + "content text, "
                    + "url varchar(4096), "
                    + "created_at timestamp default current_timestamp, "
                    + "modified_at timestamp default current_timestamp)");
            statement.execute("create table links_to_be_processed ("
                    + "id bigint auto_increment primary key, link varchar(4096))");
            statement.execute("create table links_already_processed ("
                    + "id bigint auto_increment primary key, link varchar(4096))");
        }
    }

    static void insertNews(String url, String title, String content, Instant createdAt) throws SQLException {
        String sql = "insert into news (url, title, content, created_at, modified_at) values (?, ?, ?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp timestamp = Timestamp.from(createdAt);
            statement.setString(1, url);
            statement.setString(2, title);
            statement.setString(3, content);
            statement.setTimestamp(4, timestamp);
            statement.setTimestamp(5, timestamp);
            statement.executeUpdate();
        }
    }

    static void insertLink(String table, String link) throws SQLException {
        String sql = "insert into " + table + " (link) values (?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, link);
            statement.executeUpdate();
        }
    }
}
