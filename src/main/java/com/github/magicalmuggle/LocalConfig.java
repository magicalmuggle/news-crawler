package com.github.magicalmuggle;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

// 本地数据库配置，优先级从高到低：
// 1. 环境变量 NEWS_CRAWLER_DB_URL / NEWS_CRAWLER_DB_USERNAME / NEWS_CRAWLER_DB_PASSWORD
// 2. 工作目录下的 local.properties（已被 .gitignore 忽略，参考 local.properties.example）
// 3. 内置默认值 localhost:3306，账号密码 root
// local.properties 只用于本地开发，绝不能提交到仓库。
public final class LocalConfig {
    public static final String URL_KEY = "db.url";
    public static final String USERNAME_KEY = "db.username";
    public static final String PASSWORD_KEY = "db.password";

    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/news?characterEncoding=utf8";
    private static final String DEFAULT_USERNAME = "root";
    private static final String DEFAULT_PASSWORD = "root";
    private static final String CONFIG_FILE = "local.properties";

    private LocalConfig() {
    }

    // 只读取 local.properties，不含任何默认值。CrawlConfig 等也复用这个方法。
    public static Properties loadLocalProperties() {
        Properties properties = new Properties();

        Path configFile = resolveConfigFile();
        if (configFile != null) {
            try (InputStream inputStream = Files.newInputStream(configFile);
                    InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load " + configFile, e);
            }
        }
        return properties;
    }

    public static Properties loadDatabaseProperties() {
        Properties properties = loadLocalProperties();
        setDefault(properties, URL_KEY, DEFAULT_URL);
        setDefault(properties, USERNAME_KEY, DEFAULT_USERNAME);
        setDefault(properties, PASSWORD_KEY, DEFAULT_PASSWORD);

        overrideFromEnvironment(properties, URL_KEY, "NEWS_CRAWLER_DB_URL");
        overrideFromEnvironment(properties, USERNAME_KEY, "NEWS_CRAWLER_DB_USERNAME");
        overrideFromEnvironment(properties, PASSWORD_KEY, "NEWS_CRAWLER_DB_PASSWORD");
        return properties;
    }

    private static Path resolveConfigFile() {
        String configuredPath = System.getenv("NEWS_CRAWLER_DB_CONFIG");
        Path path;
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            path = Paths.get(CONFIG_FILE);
        } else {
            path = Paths.get(configuredPath);
        }
        return Files.isReadable(path) ? path : null;
    }

    private static void setDefault(Properties properties, String key, String value) {
        if (properties.getProperty(key) == null) {
            properties.setProperty(key, value);
        }
    }

    private static void overrideFromEnvironment(Properties properties, String key, String environmentVariable) {
        String value = System.getenv(environmentVariable);
        if (value != null && !value.isEmpty()) {
            properties.setProperty(key, value);
        }
    }
}
