package com.github.magicalmuggle;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.IOException;
import java.io.InputStream;

public class MyBatisCrawlerDao implements CrawlerDao {
    private final SqlSessionFactory sqlSessionFactory;

    public MyBatisCrawlerDao() {
        try {
            String resource = "db/mybatis/config.xml";
            InputStream inputStream = Resources.getResourceAsStream(resource);
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public synchronized String getNextLinkThenDelete() {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            MyMapper mapper = sqlSession.getMapper(MyMapper.class);
            String link = mapper.selectNextLinkToBeProcessed();
            if (link != null) {
                mapper.deleteLinkToBeProcessed(link);
            }
            return link;
        }
    }

    @Override
    public boolean isLinkProcessed(String link) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            MyMapper mapper = sqlSession.getMapper(MyMapper.class);
            Integer count = mapper.countLinkAlreadyProcessed(link);
            return count > 0;
        }
    }

    @Override
    public void insertNewsIntoDatabase(String url, String title, String content) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            MyMapper mapper = sqlSession.getMapper(MyMapper.class);
            mapper.insertNews(new News(url, title, content));
        }
    }

    private void insertLink(String tableName, String link) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            MyMapper mapper = sqlSession.getMapper(MyMapper.class);
            mapper.insertLink(tableName, link);
        }
    }

    @Override
    public void insertLinkToBeProcessed(String link) {
        insertLink("links_to_be_processed", link);
    }

    @Override
    public void insertLinkAlreadyProcessed(String link) {
        insertLink("links_already_processed", link);
    }
}
