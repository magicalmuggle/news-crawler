# news-crawler

[English](https://github.com/magicalmuggle/news-crawler/) | 简体中文

[![GitHub license](https://img.shields.io/github/license/magicalmuggle/news-crawler)](https://github.com/magicalmuggle/news-crawler/blob/main/LICENSE)
[![CircleCI](https://img.shields.io/circleci/build/github/magicalmuggle/news-crawler)](https://app.circleci.com/pipelines/github/magicalmuggle/news-crawler)


本项目是一个多线程新闻爬虫程序，使用 Elasticsearch 作为搜索引擎。

## 准备工作

1. 克隆项目：
```shell
# 切换到用来存储项目的目录
cd ~/Projects/
git clone https://github.com/magicalmuggle/news-crawler
```

2. 使用 [Maven](https://maven.apache.org/) 来安装依赖：
```shell
cd ~/Projects/news-crawler/
mvn install
```

3. 使用 [Docker](https://www.docker.com/) 来运行 [MySQL](https://www.mysql.com/)：
```shell
# 切换到用来持久化数据的目录
cd ~/Docker-Data/ && mkdir mysql-data
# 把 local.properties.example 复制为 local.properties 并填写数据库地址和密码（该文件已被 .gitignore 忽略）
docker run --name mysql -p 3306:3306 -v ${PWD}/mysql-data:/var/lib/mysql -e MYSQL_ROOT_PASSWORD=my-secret-pw -d mysql:8
```

4. 使用 [Flyway](https://flywaydb.org/) 来迁移数据库：
```shell
mysql -u root -p
mysql> create database news;
cd ~/Projects/news-crawler/
mvn flyway:migrate
# 非默认数据库：mvn flyway:migrate -Ddb.url=... -Ddb.username=... -Ddb.password=...
```

5. 使用 [Docker](https://www.docker.com/) 来运行 [Elasticsearch](https://www.elastic.co/)：
```shell
# 切换到用来持久化数据的目录
cd ~/Docker-Data/ && mkdir es-data
docker run -d --name es -p 9200:9200 -p 9300:9300 -v ${PWD}/es-data:/usr/share/elasticsearch/data -e "discovery.type=single-node" elasticsearch:8.2.1
# 把 http_ca.crt 复制到项目
docker cp es:/usr/share/elasticsearch/config/certs/http_ca.crt ~/Projects/new-crawler/
# 为用户 elastic 生成密码
# 记得修改项目中的 Elasticsearch 密码
docker exec -it es /usr/share/elasticsearch/bin/elasticsearch-reset-password -u elastic
```

## 用法

运行 `com.github.magicalmuggle.Main` 来爬取新闻：

[![Crawl-news.png](https://s1.ax1x.com/2022/05/28/XKwe4H.png)](https://imgtu.com/i/XKwe4H)

默认抓取节奏比较保守：3 个线程、全局 1 请求/秒（带抖动）、单次最多 500 页；
连续 10 次 403/429/503 会自动熔断停止。这些参数由 `CrawlConfig` 读取，
可以用 `-Dcrawler.ratePerSecond=0.5` 之类的系统属性、`NEWS_CRAWLER_RATE_PER_SECOND`
环境变量或 `local.properties` 覆盖（可配置项见 `local.properties.example`）。

运行 `com.github.magicalmuggle.MockDataGenerator` 来为 MySQL 生成模拟数据：

[![Mock-data-for-MySQL.png](https://s1.ax1x.com/2022/05/28/XKBP0K.png)](https://imgtu.com/i/XKBP0K)

运行 `com.github.magicalmuggle.ElasticsearchDataGenerator` 来为 Elasticsearch 生成模拟数据：

[![Mock-data-for-Elasticsearch.png](https://s1.ax1x.com/2022/05/28/XKBRnx.png)](https://imgtu.com/i/XKBRnx)

运行 `com.github.magicalmuggle.ElasticsearchEngine` 来搜索新闻：

[![Search-news.png](https://s1.ax1x.com/2022/05/28/XK6me1.png)](https://imgtu.com/i/XK6me1)
