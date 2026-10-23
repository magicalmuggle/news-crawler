-- 每次运行的参数快照与结果。参数必须存快照而不是引用配置：配置会变，历史不能变。
create table crawl_runs
(
    id                        bigint primary key auto_increment,
    started_at                timestamp    not null,
    finished_at               timestamp    null,
    threads                   int          not null,
    requests_per_second       double       not null,
    max_pages                 int          not null,
    max_retries               int          not null,
    retry_backoff_millis      bigint       not null,
    circuit_breaker_threshold int          not null,
    attempted_pages           int          not null default 0,
    news_stored               int          not null default 0,
    dropped_links             int          not null default 0,
    remaining_pages           int          not null default 0,
    consecutive_blocks        int          not null default 0,
    stop_reason               varchar(32)  null
) default charset=utf8mb4;
