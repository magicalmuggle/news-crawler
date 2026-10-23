package com.github.magicalmuggle.web;

import org.apache.ibatis.annotations.Param;

import java.util.List;

// failed_links 的读取与「重新入队」。
public interface FailedLinkMapper {
    List<FailedLink> selectFailedLinks(@Param("limit") int limit);

    Long countFailedLinks();

    FailedLink selectFailedLink(@Param("id") long id);

    void insertLinkToBeProcessed(@Param("link") String link);

    void deleteFailedLink(@Param("id") long id);
}
