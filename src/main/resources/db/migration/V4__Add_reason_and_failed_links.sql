-- 让「已处理」和「已死」可以区分：正常抓取、404/410、解析跳过各有自己的 reason。
alter table links_already_processed
    add column reason varchar(32) null;

-- 被拦 / 网络错误重试耗尽后仍失败的链接。它们不会写入 links_already_processed，
-- 这样下一轮从首页重新发现时还有机会重爬；这张表只用于在界面上展示和手动重试。
create table failed_links
(
    id          bigint primary key auto_increment,
    link        varchar(4096) not null,
    status_code int          null,
    outcome     varchar(32)  null,
    created_at  timestamp    not null default current_timestamp,
    key idx_link (link(191)),
    key idx_created_at (created_at)
) default charset=utf8mb4;
