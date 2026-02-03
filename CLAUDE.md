# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

这是一个多线程新闻爬虫项目,专门针对新浪新闻(sina.cn)进行内容抓取。项目使用 MySQL 存储爬取数据,Elasticsearch 提供全文搜索功能。

## 构建和开发命令

### 依赖安装
```bash
mvn install
```

### 代码质量检查
```bash
# 运行 Checkstyle 和 SpotBugs
mvn verify

# 仅运行 Checkstyle
mvn checkstyle:check

# 仅运行 SpotBugs
mvn spotbugs:check
```

### 测试
```bash
# 运行所有测试
mvn test

# 运行单个测试类
mvn test -Dtest=SmokeTest

# 运行测试时使用 UTF-8 编码(已配置在 pom.xml 中)
mvn test -Dfile.encoding=UTF-8
```

### 数据库迁移
```bash
# 使用 Flyway 执行数据库迁移
mvn flyway:migrate

# 注意:需要在启动 MySQL 和创建 database 后执行
```

### 运行主程序
```bash
# 爬取新闻
mvn exec:java -Dexec.mainClass="com.github.magicalmuggle.Main"

# 为 MySQL 生成测试数据
mvn exec:java -Dexec.mainClass="com.github.magicalmuggle.MockDataGenerator"

# 为 Elasticsearch 生成测试数据
mvn exec:java -Dexec.mainClass="com.github.magicalmuggle.ElasticsearchDataGenerator"

# 搜索新闻
mvn exec:java -Dexec.mainClass="com.github.magicalmuggle.ElasticsearchEngine"
```

## 架构说明

### 核心组件

1. **Main** (`Main.java`) - 程序入口
   - 根据可用处理器数量计算线程数 (2 × CPU 核心数)
   - 先单线程爬取首页,为 URL 池填充初始链接
   - 启动多个爬虫线程并行工作

2. **Crawler** (`Crawler.java`) - 爬虫线程实现
   - 从数据库获取待处理 URL (生产者-消费者模式)
   - 使用 Apache HttpClient5 发送 HTTP 请求
   - 使用 Jsoup 解析 HTML 提取内容和链接
   - URL 清理:移除 `{}` 等畸形字符
   - URL 过滤规则:
     - 只处理 sina.cn 域名
     - 排除 passport/edu/auto/gu/ts.gd/travel 等子域名
     - 排除 JavaScript 链接和 PHP 文件
     - 跳过 jiaju 和 games 子域名

3. **数据访问层** (`CrawlerDao` 接口 + `MyBatisCrawlerDao` 实现)
   - `getNextLinkThenDelete()` - 从队列获取并删除下一个 URL
   - `insertNewsIntoDatabase()` - 保存新闻到数据库
   - `isLinkProcessed()` - 检查 URL 是否已处理
   - `insertLinkToBeProcessed()` - 添加 URL 到待处理队列
   - `insertLinkAlreadyProcessed()` - 标记 URL 为已处理
   - 使用 synchronized 保证线程安全

4. **Elasticsearch 集成** (`ElasticsearchUtil`)
   - 使用 HTTPS + 证书认证连接
   - 索引名称: `news`
   - 支持批量操作和多线程数据导入

### 数据库表结构

- **news**: 存储新闻内容 (id, title, content, url, created_at, modified_at)
- **links_to_be_processed**: 待爬取 URL 队列
- **links_already_processed**: 已处理 URL 记录(避免重复爬取)

### MyBatis 配置

- 配置文件: `src/main/resources/db/mybatis/config.xml`
- Mapper XML: `src/main/resources/db/mybatis/mapper/MyMapper.xml`
- 使用 MySQL 连接池
- 支持驼峰命名自动映射

## 环境依赖

### Docker 服务
- MySQL 8.0 (端口 3306)
- Elasticsearch 8.2.1 (端口 9200)
- 使用 `host.docker.internal` 进行容器网络通信

### 关键配置文件
- `pom.xml` - Maven 配置,Flyway 数据库凭据
- `src/main/resources/db/mybatis/config.xml` - MyBatis 数据库配置
- `src/main/resources/db/mybatis/mapper/MyMapper.xml` - SQL 映射

## 开发注意事项

1. **线程安全**: `MyBatisCrawlerDao` 的所有方法都已同步,多线程环境下可安全调用

2. **URL 处理**: 在 `Crawler.java:107-111` 的 `cleanUrl()` 方法中清理畸形 URL 字符

3. **爬虫策略**: 首页必须在多线程爬取前单独处理一次,否则 URL 池为空会导致线程直接退出

4. **编码**: 项目使用 UTF-8 编码,数据库迁移也使用 UTF-8

5. **Maven Profile**: 默认使用阿里云镜像,可通过 `-P mavenCentral` 切换到 Maven Central

6. **代码质量**:
   - Checkstyle 配置位于 `.circleci/checkstyle.xml`
   - SpotBugs 注解使用 `@SuppressFBWarnings`
