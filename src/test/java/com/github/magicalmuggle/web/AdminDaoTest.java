package com.github.magicalmuggle.web;

import com.github.magicalmuggle.News;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminDaoTest {
    private SqlSessionFactory sqlSessionFactory;
    private AdminDao dao;

    @BeforeEach
    void setUp() throws Exception {
        H2TestSupport.resetSchema();
        sqlSessionFactory = H2TestSupport.buildFactory();
        dao = new AdminDao(sqlSessionFactory);
    }

    @AfterEach
    void tearDown() {
        dao.close();
    }

    @Test
    void countsAndPagesNewsWithKeysetPagination() throws Exception {
        H2TestSupport.insertNews("https://sina.cn/1", "第一条", "正文一", Instant.parse("2026-10-19T02:00:00Z"));
        H2TestSupport.insertNews("https://sina.cn/2", "第二条", "正文二", Instant.parse("2026-10-20T02:00:00Z"));
        H2TestSupport.insertNews("https://sina.cn/3", "第三条", "正文三", Instant.parse("2026-10-20T03:00:00Z"));

        assertEquals(3L, dao.countNews());
        assertEquals(2L, dao.countNewsSince(Instant.parse("2026-10-20T00:00:00Z")));
        assertEquals(Instant.parse("2026-10-20T03:00:00Z").toEpochMilli(),
                dao.latestNewsCreatedAt().toEpochMilli());

        List<NewsSummary> page = dao.pageNews(null, null, 2);
        assertEquals(2, page.size());
        assertEquals("第三条", page.get(0).getTitle());
        assertNotNull(page.get(0).getId());
        assertEquals(3, page.get(0).getContentLength());
        assertEquals("第二条", page.get(1).getTitle());

        Long cursor = page.get(1).getId();
        List<NewsSummary> nextPage = dao.pageNews(cursor, null, 2);
        assertEquals(1, nextPage.size());
        assertEquals("第一条", nextPage.get(0).getTitle());
    }

    @Test
    void filtersNewsByTitlePattern() throws Exception {
        H2TestSupport.insertNews("https://sina.cn/1", "足球新闻", "正文", Instant.parse("2026-10-20T02:00:00Z"));
        H2TestSupport.insertNews("https://sina.cn/2", "篮球新闻", "正文", Instant.parse("2026-10-20T03:00:00Z"));

        List<NewsSummary> matched = dao.pageNews(null, "%足球%", 10);
        assertEquals(1, matched.size());
        assertEquals("足球新闻", matched.get(0).getTitle());
        assertEquals(0, dao.pageNews(null, "%网球%", 10).size());
    }

    @Test
    void readsNewsDetailAndReturnsNullWhenMissing() throws Exception {
        H2TestSupport.insertNews("https://sina.cn/1", "唯一一条", "完整正文", Instant.parse("2026-10-20T02:00:00Z"));
        NewsSummary news = dao.pageNews(null, null, 1).get(0);

        News loaded = dao.findNews(news.getId());
        assertEquals("完整正文", loaded.getContent());
        assertNull(dao.findNews(999999L));
    }

    @Test
    void countsLinksAndFindsDuplicates() throws Exception {
        H2TestSupport.insertLink("links_to_be_processed", "https://sina.cn/a");
        H2TestSupport.insertLink("links_to_be_processed", "https://sina.cn/dup");
        H2TestSupport.insertLink("links_to_be_processed", "https://sina.cn/dup");
        H2TestSupport.insertLink("links_already_processed", "https://sina.cn/done");

        assertEquals(3L, dao.countLinksPending());
        assertEquals(1L, dao.countLinksProcessed());

        List<Map<String, Object>> duplicates = dao.duplicatePendingLinks(10);
        assertEquals(1, duplicates.size());
        assertEquals("https://sina.cn/dup", duplicates.get(0).get("link"));
        assertEquals(2L, ((Number) duplicates.get(0).get("cnt")).longValue());

        assertEquals(1, dao.processedLinks("%done%", 10).size());
        assertEquals(0, dao.processedLinks("%missing%", 10).size());
    }

    @Test
    void pingsTheDatabase() {
        assertTrue(dao.ping());
    }
}
