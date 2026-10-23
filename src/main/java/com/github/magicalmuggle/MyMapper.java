package com.github.magicalmuggle;

import org.apache.ibatis.annotations.Param;

public interface MyMapper {
    String selectNextLinkToBeProcessed();

    void deleteLinkToBeProcessed(@Param("link") String link);

    Integer countLinkAlreadyProcessed(@Param("link") String link);

    void insertNews(News news);

    void insertLink(@Param("tableName") String tableName, @Param("link") String link);

    void insertProcessedLink(@Param("link") String link, @Param("reason") String reason);

    void insertFailedLink(@Param("link") String link, @Param("statusCode") int statusCode,
            @Param("outcome") String outcome);
}
