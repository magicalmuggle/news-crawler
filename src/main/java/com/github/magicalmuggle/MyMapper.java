package com.github.magicalmuggle;

import org.apache.ibatis.annotations.Param;

public interface MyMapper {
    String selectNextLinkToBeProcessed();

    void deleteLinkToBeProcessed(@Param("link") String link);

    Integer countLinkAlreadyProcessed(@Param("link") String link);

    void insertNews(News news);

    void insertLink(@Param("tableName") String tableName, @Param("link") String link);
}
