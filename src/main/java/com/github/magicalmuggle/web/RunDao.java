package com.github.magicalmuggle.web;

import com.github.magicalmuggle.MyBatisUtil;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import javax.sql.DataSource;
import java.util.List;

// 运行历史的持久化。写操作都单独提交，和爬虫「每个写操作一个 session」的约定一致。
public class RunDao {
    private final SqlSessionFactory sqlSessionFactory;

    public RunDao() {
        this(MyBatisUtil.getSqlSessionFactory());
    }

    RunDao(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public void insert(RunRecord record) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(RunMapper.class).insertRun(record);
        }
    }

    public void finish(RunRecord record) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(RunMapper.class).finishRun(record);
        }
    }

    public RunRecord find(long id) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(RunMapper.class).selectRun(id);
        }
    }

    public List<RunRecord> recent(int limit) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(RunMapper.class).selectRuns(limit);
        }
    }

    public void close() {
        DataSource dataSource = sqlSessionFactory.getConfiguration().getEnvironment().getDataSource();
        if (dataSource instanceof PooledDataSource) {
            ((PooledDataSource) dataSource).forceCloseAll();
        }
    }
}
