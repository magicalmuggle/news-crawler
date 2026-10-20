package com.github.magicalmuggle.web;

import com.github.magicalmuggle.MyBatisUtil;
import com.github.magicalmuggle.News;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// 只读数据访问。SqlSessionFactory 只构造一次：每次请求重建会把连接池也重建一遍。
public class AdminDao {
    private final SqlSessionFactory sqlSessionFactory;

    public AdminDao() {
        this(MyBatisUtil.getSqlSessionFactory());
    }

    AdminDao(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public long countNews() {
        Long value = query(AdminMapper::countNews);
        return value == null ? 0L : value;
    }

    public long countNewsSince(Instant since) {
        Long value = query(mapper -> mapper.countNewsSince(since));
        return value == null ? 0L : value;
    }

    public Instant latestNewsCreatedAt() {
        return query(AdminMapper::latestNewsCreatedAt);
    }

    public News findNews(long id) {
        return query(mapper -> mapper.selectNewsById(id));
    }

    public List<NewsSummary> pageNews(Long beforeId, String pattern, int limit) {
        return query(mapper -> mapper.selectNewsPage(beforeId, pattern, limit));
    }

    public long countLinksPending() {
        Long value = query(AdminMapper::countLinksPending);
        return value == null ? 0L : value;
    }

    public List<String> pendingLinks(int limit) {
        return query(mapper -> mapper.selectPendingLinks(limit));
    }

    public List<Map<String, Object>> duplicatePendingLinks(int limit) {
        return query(mapper -> mapper.selectDuplicatePendingLinks(limit));
    }

    public long countLinksProcessed() {
        Long value = query(AdminMapper::countLinksProcessed);
        return value == null ? 0L : value;
    }

    public List<String> processedLinks(String pattern, int limit) {
        return query(mapper -> mapper.selectProcessedLinks(pattern, limit));
    }

    public boolean ping() {
        Integer value = query(AdminMapper::ping);
        return value != null;
    }

    public String databaseName() {
        return query(AdminMapper::selectDatabaseName);
    }

    public String databaseVersion() {
        return query(AdminMapper::selectDatabaseVersion);
    }

    public String flywayVersion() {
        return query(AdminMapper::selectFlywayVersion);
    }

    public void close() {
        DataSource dataSource = sqlSessionFactory.getConfiguration().getEnvironment().getDataSource();
        if (dataSource instanceof PooledDataSource) {
            ((PooledDataSource) dataSource).forceCloseAll();
        }
    }

    private <T> T query(Query<T> query) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            return query.run(sqlSession.getMapper(AdminMapper.class));
        }
    }

    private interface Query<T> {
        T run(AdminMapper mapper);
    }
}
