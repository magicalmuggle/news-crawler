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
# Remember to change the database password in the project
docker run --name mysql -p 3306:3306 -v ${PWD}/mysql-data:/var/lib/mysql -e MYSQL_ROOT_PASSWORD=my-secret-pw -d mysql:8
```

4. Use [Flyway](https://flywaydb.org/) to migrate the database:
```shell
mysql -u root -p
mysql> create database news;
cd ~/Projects/news-crawler/
mvn flyway:migrate
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

Generate mock data for MySQL by running `com.github.magicalmuggle.MockDataGenerator`:

[![Mock-data-for-MySQL.png](https://s1.ax1x.com/2022/05/28/XKBP0K.png)](https://imgtu.com/i/XKBP0K)

Generate mock data for Elasticsearch by running `com.github.magicalmuggle.ElasticsearchDataGenerator`:

[![Mock-data-for-Elasticsearch.png](https://s1.ax1x.com/2022/05/28/XKBRnx.png)](https://imgtu.com/i/XKBRnx)

Search news by running `com.github.magicalmuggle.ElasticsearchEngine`:

[![Search-news.png](https://s1.ax1x.com/2022/05/28/XK6me1.png)](https://imgtu.com/i/XK6me1)
