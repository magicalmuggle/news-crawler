package com.github.magicalmuggle.web;

import org.apache.ibatis.annotations.Param;

import java.util.List;

// crawl_runs 的读写。只写运行历史，不碰爬虫的三张业务表。
public interface RunMapper {
    void insertRun(RunRecord record);

    void finishRun(RunRecord record);

    RunRecord selectRun(@Param("id") long id);

    List<RunRecord> selectRuns(@Param("limit") int limit);
}
