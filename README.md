# news-crawler

English | [简体中文](https://github.com/magicalmuggle/news-crawler/blob/main/README_CN.md)

[![GitHub license](https://img.shields.io/github/license/magicalmuggle/news-crawler)](https://github.com/magicalmuggle/news-crawler/blob/main/LICENSE)
[![CircleCI](https://img.shields.io/circleci/build/github/magicalmuggle/news-crawler)](https://app.circleci.com/pipelines/github/magicalmuggle/news-crawler)

This project is a multi-threaded news crawler that uses Elasticsearch as a search engine.

## Preparation

1. Clone the project: 
```shell
# Change to the directory used to store the project
cd ~/Projects/
git clone https://github.com/magicalmuggle/news-crawler
```

2. Use [Maven](https://maven.apache.org/) to install dependencies:
```shell
cd ~/Projects/news-crawler/
mvn install
```

3. Use [Docker](https://www.docker.com/) to run [MySQL](https://www.mysql.com/):

```shell
# Change to the directory used to persist the data
cd ~/Docker-Data/ && mkdir mysql-data
# Copy local.properties.example to local.properties and fill in the database url/password (git-ignored)
docker run --name mysql -p 3306:3306 -v ${PWD}/mysql-data:/var/lib/mysql -e MYSQL_ROOT_PASSWORD=my-secret-pw -d mysql:8
```

4. Use [Flyway](https://flywaydb.org/) to migrate the database:
```shell
mysql -u root -p
mysql> create database news;
cd ~/Projects/news-crawler/
mvn flyway:migrate
# Non-default database: mvn flyway:migrate -Ddb.url=... -Ddb.username=... -Ddb.password=...
```

5. Use [Docker](https://www.docker.com/) to run [Elasticsearch](https://www.elastic.co/):
```shell
# Change to the directory used to persist the data
cd ~/Docker-Data/ && mkdir es-data
docker run -d --name es -p 9200:9200 -p 9300:9300 -v ${PWD}/es-data:/usr/share/elasticsearch/data -e "discovery.type=single-node" elasticsearch:8.2.1
# Copy http_ca.crt to the project
docker cp es:/usr/share/elasticsearch/config/certs/http_ca.crt ~/Projects/new-crawler/
# Generate password for user elastic
# Remember to change the Elasticsearch password in the project.
docker exec -it es /usr/share/elasticsearch/bin/elasticsearch-reset-password -u elastic
```

## Usage

Crawl news by running `com.github.magicalmuggle.Main`:

[![Crawl-news.png](https://s1.ax1x.com/2022/05/28/XKwe4H.png)](https://imgtu.com/i/XKwe4H)

Crawling is deliberately slow by default: 3 threads, a global 1 request/second (with jitter),
and at most 500 pages per run. Ten consecutive 403/429/503 responses trip a circuit breaker
that stops the crawl. These values come from `CrawlConfig` and can be overridden with
`-Dcrawler.ratePerSecond=0.5` style system properties, `NEWS_CRAWLER_*` environment variables,
or `local.properties` (see `local.properties.example`).

Generate mock data for MySQL by running `com.github.magicalmuggle.MockDataGenerator`:

[![Mock-data-for-MySQL.png](https://s1.ax1x.com/2022/05/28/XKBP0K.png)](https://imgtu.com/i/XKBP0K)

Generate mock data for Elasticsearch by running `com.github.magicalmuggle.ElasticsearchDataGenerator`:

[![Mock-data-for-Elasticsearch.png](https://s1.ax1x.com/2022/05/28/XKBRnx.png)](https://imgtu.com/i/XKBRnx)

Search news by running `com.github.magicalmuggle.ElasticsearchEngine`:

[![Search-news.png](https://s1.ax1x.com/2022/05/28/XK6me1.png)](https://imgtu.com/i/XK6me1)

### Admin UI

Run `com.github.magicalmuggle.web.AdminMain` from your IDE and open <http://127.0.0.1:8080/>.
It gives you news browsing, the link queue, log tailing, an environment check, a **Run** page
that starts and stops a crawl with live progress (threads, pages, circuit-breaker count via SSE),
plus Elasticsearch search and failed-link retry:

```shell
# optional: change the port / bind address (loopback only by default)
# --port 8080 --bind 127.0.0.1
```

Apply the database migrations once (V3 run history, V4 dead-link reason and failed links):

```shell
mvn flyway:migrate
```

You must run the migrations when upgrading an existing database: V4 adds the `reason` column to
`links_already_processed`, and the crawler cannot mark links as processed without it.

Starting the server does not crawl anything; the crawl only runs when you press "开始抓取" in the UI,
and then it really hits sina.cn and writes to the database. The UI reads the database settings from
`local.properties` and the log file at `logs/crawler.log`. It has no authentication, so do not bind it
to a public address. See [docs/web-ui.md](docs/web-ui.md) for the design notes.
