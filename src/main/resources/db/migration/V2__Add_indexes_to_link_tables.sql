-- links_to_be_processed / links_already_processed 原先既没有主键也没有索引，
-- 而 DAO 每次都要按 link 做全表扫描的 count 和 delete；表变大后数据库会先于网络成为瓶颈。
-- 这里只加普通前缀索引，不改成唯一索引：varchar(4096) 只能用前缀建唯一索引，
-- 前缀相同但实际不同的 URL 会被误判为重复，导致丢链接。去重仍由 isLinkProcessed 负责。
alter table links_to_be_processed
    add column id bigint primary key auto_increment,
    add key idx_link (link(191));

alter table links_already_processed
    add column id bigint primary key auto_increment,
    add key idx_link (link(191));
