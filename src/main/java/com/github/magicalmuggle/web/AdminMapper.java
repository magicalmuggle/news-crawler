package com.github.magicalmuggle.web;

import com.github.magicalmuggle.News;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// Web 层的只读查询。与 MyMapper 分开，保证管理界面永远碰不到爬虫的写路径。
public interface AdminMapper {
    Long countNews();

    Long countNewsSince(@Param("since") Instant since);

    Instant latestNewsCreatedAt();

    News selectNewsById(@Param("id") long id);

    List<NewsSummary> selectNewsPage(@Param("beforeId") Long beforeId, @Param("pattern") String pattern,
            @Param("limit") int limit);

    Long countLinksPending();

    List<String> selectPendingLinks(@Param("limit") int limit);

    List<Map<String, Object>> selectDuplicatePendingLinks(@Param("limit") int limit);

    Long countLinksProcessed();

    List<ProcessedLink> selectProcessedLinks(@Param("pattern") String pattern, @Param("limit") int limit);

    Integer ping();

    String selectDatabaseName();

    String selectDatabaseVersion();

    String selectFlywayVersion();
}
