# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 提供该代码库的工作指导。

## 项目概述

这是一个基于 Java 的多线程新闻爬虫项目，专门针对新浪新闻 (sina.cn) 进行内容抓取。项目采用经典的生产者-消费者模式，使用 MySQL 存储爬取数据，Elasticsearch 提供全文搜索功能。

**技术栈:**
- Java 8
- Maven 构建工具
- MySQL 8.0 + MyBatis (数据持久层)
- Elasticsearch 8.2.1 (搜索引擎)
- Apache HttpClient 5 (HTTP 客户端)
- Jsoup (HTML 解析)
- Flyway (数据库迁移)
- JUnit 5 (测试框架)

---

## 快速开始

### 1. 环境准备

启动依赖服务 (Docker):

```bash
# MySQL 8.0
docker run --name mysql -p 3306:3306 \
  -v ~/docker-data/mysql:/var/lib/mysql \
  -e MYSQL_ROOT_PASSWORD=roottoor \
  -d mysql:8

# Elasticsearch 8.2.1
docker run -d --name es -p 9200:9200 \
  -v ~/docker-data/es:/usr/share/elasticsearch/data \
  -e "discovery.type=single-node" \
  elasticsearch:8.2.1

# 复制证书并生成密码
docker cp es:/usr/share/elasticsearch/config/certs/http_ca.crt ./
docker exec -it es /usr/share/elasticsearch/bin/elasticsearch-reset-password -u elastic
```

### 2. 数据库初始化

```bash
# 创建数据库
mysql -h host.docker.internal -u root -p -e "CREATE DATABASE news CHARACTER SET utf8mb4;"

# 执行 Flyway 迁移
mvn flyway:migrate
```

### 3. 构建与运行

```bash
# 安装依赖
mvn install

# 运行爬虫主程序
mvn exec:java -Dexec.mainClass="com.github.magicalmuggle.Main"
```

---

## 项目结构

```
news-crawler/
├── pom.xml                          # Maven 配置
├── .circleci/
│   └── checkstyle.xml              # Checkstyle 代码风格配置
├── src/
│   ├── main/
│   │   ├── java/com/github/magicalmuggle/
│   │   │   ├── Main.java                    # 程序入口
│   │   │   ├── Crawler.java                 # 爬虫线程实现
│   │   │   ├── CrawlerDao.java              # 数据访问接口
│   │   │   ├── MyBatisCrawlerDao.java       # MyBatis 实现
│   │   │   ├── JdbcCrawlerDao.java          # JDBC 实现 (备用)
│   │   │   ├── News.java                    # 新闻实体类
│   │   │   ├── MyBatisUtil.java             # MyBatis 工具类
│   │   │   ├── MyMapper.java                # MyBatis Mapper 接口
│   │   │   ├── MockMapper.java              # 测试数据 Mapper
│   │   │   ├── MockDataGenerator.java       # MySQL 测试数据生成器
│   │   │   ├── ElasticsearchUtil.java       # ES 客户端工具
│   │   │   ├── ElasticsearchDataGenerator.java  # ES 测试数据导入
│   │   │   └── ElasticsearchEngine.java     # ES 搜索控制台
│   │   └── resources/
│   │       └── db/
│   │           ├── mybatis/
│   │           │   ├── config.xml           # MyBatis 配置
│   │           │   ├── MyMapper.xml         # 爬虫 SQL 映射
│   │           │   └── MockMapper.xml       # 测试数据 SQL 映射
│   │           └── migration/
│   │               └── V1__Create_tables.sql    # Flyway 初始迁移
│   └── test/java/com/github/magicalmuggle/
│       └── SmokeTest.java           # 冒烟测试
```

---

## 架构详解

### 核心组件

#### 1. Main (程序入口)

**文件:** `Main.java`

- 根据 CPU 核心数计算线程池大小 (2 × CPU 核心数)
- 先单线程爬取首页填充 URL 队列
- 启动多个爬虫线程并行工作

```java
int availableProcessors = Runtime.getRuntime().availableProcessors();
int numOfThreads = availableProcessors * 2;
```

#### 2. Crawler (爬虫线程)

**文件:** `Crawler.java`

继承 `Thread`，实现完整的爬取流程:

1. **URL 获取**: 从 `links_to_be_processed` 表获取待处理链接
2. **去重检查**: 跳过已处理的链接
3. **域名过滤**: 跳过非目标域名和排除列表中的链接
4. **HTTP 请求**: 使用 Apache HttpClient5 发送请求
5. **HTML 解析**: 使用 Jsoup 提取内容和链接
6. **数据存储**: 新闻存入 `news` 表，新链接加入待处理队列

**URL 过滤规则:**
- 只处理 `sina.cn` 域名
- 排除子域名: `passport`, `edu`, `auto`, `gu`, `ts.gd`, `travel`, `jiaju`, `games`
- 排除 JavaScript 链接 (`javascript:` 开头)
- 排除 PHP 文件 (`.php` 后缀)

**URL 清理:**
```java
private String cleanUrl(String url) {
    url = url.replace("{", "");
    url = url.replace("}", "");
    return url;
}
```

#### 3. 数据访问层

**接口:** `CrawlerDao.java`

| 方法 | 说明 |
|------|------|
| `getNextLinkThenDelete()` | 获取并删除下一个待处理 URL |
| `insertNewsIntoDatabase()` | 保存新闻到数据库 |
| `isLinkProcessed()` | 检查 URL 是否已处理 |
| `insertLinkToBeProcessed()` | 添加 URL 到待处理队列 |
| `insertLinkAlreadyProcessed()` | 标记 URL 为已处理 |

**MyBatis 实现:** `MyBatisCrawlerDao.java`
- 所有方法使用 `synchronized` 保证线程安全
- 使用 try-with-resources 自动关闭 SqlSession
- 自动提交模式 (`openSession(true)`)

**JDBC 实现:** `JdbcCrawlerDao.java`
- 纯 JDBC 实现，用于对比和学习
- 同样使用 `synchronized` 保证线程安全

#### 4. Elasticsearch 集成

**文件:** `ElasticsearchUtil.java`

- 使用 HTTPS + 证书认证连接 ES
- 支持用户名/密码认证
- 单例模式获取客户端

**配置参数:**
```java
private static final String USER_NAME = "elastic";
private static final String PASSWORD = "your-password";  // 需修改
private static final String FILE_PATH_OF_CA_CERTIFICATE = "./http_ca.crt";
```

---

## 数据库设计

### 表结构

**news** - 新闻内容表
```sql
CREATE TABLE news (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    title       TEXT,                           -- 新闻标题
    content     TEXT,                           -- 新闻内容
    url         VARCHAR(4096),                  -- 新闻链接
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) DEFAULT CHARSET=utf8mb4;
```

**links_to_be_processed** - 待处理 URL 队列
```sql
CREATE TABLE links_to_be_processed (
    link VARCHAR(4096)
) DEFAULT CHARSET=utf8mb4;
```

**links_already_processed** - 已处理 URL 记录 (去重)
```sql
CREATE TABLE links_already_processed (
    link VARCHAR(4096)
) DEFAULT CHARSET=utf8mb4;
```

---

## 常用命令

### 构建与测试

```bash
# 编译项目
mvn compile

# 运行测试
mvn test

# 运行单个测试类
mvn test -Dtest=SmokeTest

# 打包
mvn package
```

### 代码质量检查

```bash
# 运行所有检查 (Checkstyle + SpotBugs)
mvn verify

# 仅运行 Checkstyle
mvn checkstyle:check

# 仅运行 SpotBugs
mvn spotbugs:check
```

### 数据库迁移

```bash
# 执行迁移
mvn flyway:migrate

# 查看迁移状态
mvn flyway:info

# 修复失败的迁移
mvn flyway:repair
```

### 运行程序

```bash
# 爬取新闻 (主程序)
mvn exec:java -Dexec.mainClass="com.github.magicalmuggle.Main"

# 为 MySQL 生成测试数据 (100万条)
mvn exec:java -Dexec.mainClass="com.github.magicalmuggle.MockDataGenerator"

# 为 Elasticsearch 生成测试数据
mvn exec:java -Dexec.mainClass="com.github.magicalmuggle.ElasticsearchDataGenerator"

# 启动搜索控制台
mvn exec:java -Dexec.mainClass="com.github.magicalmuggle.ElasticsearchEngine"
```

### Maven Profile

```bash
# 使用阿里云镜像 (默认)
mvn install

# 使用 Maven Central
mvn install -P mavenCentral
```

---

## 配置说明

### 数据库连接配置

**MyBatis 配置:** `src/main/resources/db/mybatis/config.xml`

```xml
<dataSource type="POOLED">
    <property name="driver" value="com.mysql.cj.jdbc.Driver"/>
    <property name="url" value="jdbc:mysql://host.docker.internal:3306/news?characterEncoding=utf8"/>
    <property name="username" value="root"/>
    <property name="password" value="roottoor"/>
</dataSource>
```

**Flyway 配置:** `pom.xml`

```xml
<configuration>
    <url>jdbc:mysql://host.docker.internal/news?characterEncoding=utf-8</url>
    <user>root</user>
    <password>roottoor</password>
</configuration>
```

### Elasticsearch 配置

**文件:** `ElasticsearchUtil.java`

使用前需要修改:
1. 将 `PASSWORD` 修改为实际生成的密码
2. 确保证书文件 `http_ca.crt` 存在于项目根目录

---

## 开发指南

### 线程安全

- `MyBatisCrawlerDao` 所有公共方法都使用 `synchronized` 修饰
- 每个线程独立创建和关闭 SqlSession
- 数据库连接使用连接池管理

### URL 处理流程

1. 从页面提取所有 `<a>` 标签的 `href` 属性
2. 处理协议相对 URL (`//` 开头) → 添加 `https:`
3. 清理 URL 中的空白字符
4. 应用过滤规则 (域名、排除列表、文件类型)
5. 存入待处理队列

### 新闻识别逻辑

```java
// 查找 article 标签
Elements articleTags = doc.select("article");
if (!articleTags.isEmpty()) {
    Element articleTag = articleTags.get(0);
    String title = articleTag.child(0).text();
    String content = articleTag.select("p").stream()
        .map(Element::text)
        .collect(Collectors.joining("\n"));
    // 保存到数据库
}
```

### 添加新的数据源支持

1. 实现 `CrawlerDao` 接口
2. 在 `isRequiredLink()` 中修改域名过滤规则
3. 在 `storeIntoDatabaseIfItIsNewsPage()` 中调整内容提取逻辑

---

## 故障排查

### 常见问题

**问题:** 爬虫启动后立即退出
**原因:** URL 队列为空，线程获取不到链接
**解决:** `Main.java` 已内置首页预爬取逻辑，确保多线程启动前队列有初始链接

**问题:** MySQL 连接失败
**检查:**
- MySQL 容器是否运行: `docker ps`
- 网络是否可达: `docker.host.internal` 或改为 `localhost`
- 数据库密码是否正确

**问题:** Elasticsearch 证书错误
**解决:**
- 确保证书文件存在: `ls http_ca.crt`
- 重新复制证书: `docker cp es:/usr/share/elasticsearch/config/certs/http_ca.crt ./`
- 更新密码: 修改 `ElasticsearchUtil.java` 中的 `PASSWORD`

**问题:** 中文乱码
**解决:**
- 数据库使用 `utf8mb4` 字符集
- JDBC URL 添加 `characterEncoding=utf8`
- 确保 HTML 解析时正确处理编码

---

## 代码规范

### Checkstyle 规则

- 使用 LF 换行符
- 禁止使用 Tab 字符
- 行尾不能有空格
- 公共方法必须有 Javadoc
- 正确的修饰符顺序
- 必须使用大括号

### SpotBugs 注解

```java
// 抑制误报: 暴露内部数组/对象
@SuppressFBWarnings("EI_EXPOSE_REP2")
public Crawler(CrawlerDao dao) { ... }

// 抑制误报: 硬编码数据库密码
@SuppressFBWarnings("DMI_CONSTANT_DB_PASSWORD")
public JdbcCrawlerDao() { ... }
```

---

## 扩展功能

### 添加新的 URL 过滤器

在 `Crawler.java` 的 `isNotExcludedPageLink()` 方法中添加:

```java
private boolean isNotExcludedPageLink(String link) {
    return !(link.contains("passport.sina.cn")
            || link.contains("new-subdomain.sina.cn")  // 添加新的排除规则
            || ...);
}
```

### 修改新闻提取逻辑

针对不同网站结构调整 `storeIntoDatabaseIfItIsNewsPage()`:

```java
// 示例: 针对特定网站的提取逻辑
private void storeIntoDatabaseIfItIsNewsPage(Document doc, String link) {
    // 自定义选择器
    String title = doc.select("h1.title").text();
    String content = doc.select("div.content p").text();
    // ...
}
```

### 批量导入优化

`MockDataGenerator.java` 使用 MyBatis 批量模式:

```java
try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
    // 批量插入操作
    if (count % 2_000 == 0) {
        session.flushStatements();  // 定期刷新
    }
    session.commit();
}
```

---

## 相关文件索引

| 功能 | 文件路径 |
|------|----------|
| 程序入口 | `src/main/java/com/github/magicalmuggle/Main.java` |
| 爬虫实现 | `src/main/java/com/github/magicalmuggle/Crawler.java` |
| DAO 接口 | `src/main/java/com/github/magicalmuggle/CrawlerDao.java` |
| MyBatis 实现 | `src/main/java/com/github/magicalmuggle/MyBatisCrawlerDao.java` |
| JDBC 实现 | `src/main/java/com/github/magicalmuggle/JdbcCrawlerDao.java` |
| 新闻实体 | `src/main/java/com/github/magicalmuggle/News.java` |
| MyBatis 配置 | `src/main/resources/db/mybatis/config.xml` |
| SQL 映射 | `src/main/resources/db/mybatis/MyMapper.xml` |
| 数据库迁移 | `src/main/resources/db/migration/V1__Create_tables.sql` |
| Maven 配置 | `pom.xml` |
| 代码风格 | `.circleci/checkstyle.xml` |
