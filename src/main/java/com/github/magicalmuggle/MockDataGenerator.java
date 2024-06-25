package com.github.magicalmuggle;

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.List;
import java.util.Random;

public class MockDataGenerator {
    public static void main(String[] args) {
        SqlSessionFactory sqlSessionFactory = MyBatisUtil.getSqlSessionFactory();
        mockData(sqlSessionFactory, 1_000_000);
    }

    private static void mockData(SqlSessionFactory sqlSessionFactory, int howMany) {
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            MockMapper mapper = session.getMapper(MockMapper.class);
            Integer countOfNews = mapper.countNews();
            List<News> currentNewsList = mapper.selectNews();

            int count = howMany - countOfNews;
            Random random = new Random();
            try {
                while (count-- > 0) {
                    System.out.println("Left: " + count);

                    News newsToBeInserted = getNewsToBeInserted(currentNewsList, random);
                    mapper.insertNews(newsToBeInserted);

                    if (count % 2_000 == 0) {
                        session.flushStatements();
                    }
                }
                session.commit();
            } catch (Exception e) {
                session.rollback();
                throw new RuntimeException(e);
            }
        }
    }

    private static News getNewsToBeInserted(List<News> currentNewsList, Random random) {
        int index = random.nextInt(currentNewsList.size());
        News newsToBeInserted = new News(currentNewsList.get(index));

        Instant mockInstant = newsToBeInserted.getCreatedAt()
                .minusSeconds(random.nextInt(60 * 60 * 24 * 365));
        newsToBeInserted.setCreatedAt(mockInstant);
        newsToBeInserted.setModifiedAt(mockInstant);
        return newsToBeInserted;
    }
}
