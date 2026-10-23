package com.github.magicalmuggle.web;

import com.github.magicalmuggle.MyBatisUtil;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import javax.sql.DataSource;
import java.util.List;

// 失败链接的读取与重新入队。重新入队是「写队列表 + 删失败记录」，
// 放在同一个事务里，避免出现记录删了但链接没进队列的情况。
public class FailedLinkDao {
    private final SqlSessionFactory sqlSessionFactory;

    public FailedLinkDao() {
        this(MyBatisUtil.getSqlSessionFactory());
    }

    FailedLinkDao(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public List<FailedLink> recent(int limit) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(FailedLinkMapper.class).selectFailedLinks(limit);
        }
    }

    public long count() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Long value = session.getMapper(FailedLinkMapper.class).countFailedLinks();
            return value == null ? 0L : value;
        }
    }

    public boolean requeue(long id) {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            FailedLinkMapper mapper = session.getMapper(FailedLinkMapper.class);
            FailedLink failed = mapper.selectFailedLink(id);
            if (failed == null) {
                session.rollback();
                return false;
            }
            mapper.insertLinkToBeProcessed(failed.getLink());
            mapper.deleteFailedLink(id);
            session.commit();
            return true;
        }
    }

    public void close() {
        DataSource dataSource = sqlSessionFactory.getConfiguration().getEnvironment().getDataSource();
        if (dataSource instanceof PooledDataSource) {
            ((PooledDataSource) dataSource).forceCloseAll();
        }
    }
}
