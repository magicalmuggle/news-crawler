package com.github.magicalmuggle;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.io.IOException;
import java.util.List;

public class ElasticsearchDataGenerator {
    public static void main(String[] args) {
        SqlSessionFactory sqlSessionFactory = MyBatisUtil.getSqlSessionFactory();
        List<News> currentNewsList = getNewsFromMySQL(sqlSessionFactory);
        currentNewsList.forEach(news -> News.cutNewsContent(news, 10)); // 裁剪插入新闻的内容长度，从而节省硬盘空间

        int numOfThreads = 16;
        for (int i = 0; i < numOfThreads; i++) {
            new Thread(() -> {
                try {
                    writeSingleThread(currentNewsList);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).start();
        }
    }

    private static void writeSingleThread(List<News> currentNewsList) throws IOException {
        ElasticsearchClient client = ElasticsearchUtil.getElasticsearchClient();
        try {
            // 单线程写入 1000 * 1000 = 1_000_000 条数据
            for (int i = 0; i < 1000; i++) {
                BulkRequest.Builder BulkRequestBuilder = new BulkRequest.Builder();

                for (News news : currentNewsList) {
                    BulkRequestBuilder.operations(op -> op.index(idx -> idx.index("news").document(news)));
                }
                client.bulk(BulkRequestBuilder.build());

                System.out.println("Current thread: " + Thread.currentThread().getName() + " finishes " + i);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            client._transport().close();
        }
    }

    private static List<News> getNewsFromMySQL(SqlSessionFactory sqlSessionFactory) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            MockMapper mapper = session.getMapper(MockMapper.class);
            return mapper.selectNews();
        }
    }
}
