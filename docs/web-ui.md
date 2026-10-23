# Web UI 设计

> 状态：P0（只读）、P1（运行控制）、P2（搜索 / 规则预览 / 失败重试）均已实现。
> 范围：为现有爬虫增加一个 Web 管理界面，覆盖「观察 → 控制 → 检索」三层能力。
> 前置阅读：[AGENTS.md](../AGENTS.md) 的「运行时架构」与「数据层约定」两节。
> 落地细节见「13. P0」「14. P1」「15. P2」三节。

## 1. 背景

当前项目是纯后端 CLI：`Main` 在单进程里读配置、爬首页、起 N 个线程、`join` 后打印一行汇总就退出。
运行状态只存在于内存，历史只落在 `logs/crawler.log`，检索要另跑 `ElasticsearchEngine` 的 stdin 循环。

这带来三个具体问题：

- **看不见**：跑到一半时，除了 `tail -f logs/crawler.log` 没有任何办法知道进度、失败率、是否正在被限流。
- **控不了**：想改参数只能改 `local.properties` 或加 `-D`，重启进程；想停只能 `Ctrl-C`。
- **查不到**：失败链接、死链原因、每次运行的参数快照都没有持久化，事后无法复盘。

Web UI 的目标就是补上这三块，而不是把爬虫改造成一个分布式平台。

## 2. 现有约束及其对 UI 的影响

这些不是设计偏好，是代码现状决定的上限，先列清楚，避免做出「界面上有、数据上做不到」的功能。

| 约束 | 出处 | 对 UI 的影响 |
| --- | --- | --- |
| 运行状态全在内存，一个进程一次运行 | [Main.java](../src/main/java/com/github/magicalmuggle/Main.java)、[CrawlSession.java](../src/main/java/com/github/magicalmuggle/CrawlSession.java) | 必须有 RunManager 才能控制；不能 attach 一个已经在跑的 CLI 进程 |
| `getNextLinkThenDelete` 靠 `synchronized` 保证不重复 | [MyBatisCrawlerDao.java](../src/main/java/com/github/magicalmuggle/MyBatisCrawlerDao.java) 第 13 行 | UI 若跑在另一个 JVM 并写队列表，保证失效；必须同进程或把原子性下沉到 SQL |
| 待处理链接是破坏性消费，无时间戳、无去重 | [MyMapper.xml](../src/main/resources/db/mybatis/MyMapper.xml) | 队列页只能看「现在有什么」，不能回放历史；需要额外展示重复 URL |
| 失败链接只进日志和计数器，不入库 | [Crawler.java](../src/main/java/com/github/magicalmuggle/Crawler.java) 的 `processLink` 分支 | 「失败清单 / 一键重试」必须先加表，纯 UI 做不到 |
| 死链（404/410）与正常链接混在同一张表 | `links_already_processed` 无状态列 | 无法区分「抓过」和「抓过但已死」，需要加列或单独建表 |
| `news` 除主键外无索引 | [V1__Create_tables.sql](../src/main/resources/db/migration/V1__Create_tables.sql) | 列表必须键集分页；排序、按时间过滤都要先补索引 |
| 参数优先级：系统属性 > 环境变量 > local.properties > 默认值 | [CrawlConfig.java](../src/main/java/com/github/magicalmuggle/CrawlConfig.java) 第 5–10 行 | 「为什么我改了配置没生效」是最常见的坑，配置页必须显示生效值与来源 |
| 限速与熔断是全局的，不是每线程的 | [CrawlThrottle.java](../src/main/java/com/github/magicalmuggle/CrawlThrottle.java)、[CircuitBreaker.java](../src/main/java/com/github/magicalmuggle/CircuitBreaker.java) | 监控页按「运行」聚合，不要做每线程速率图 |
| 单次运行有页数预算，熔断即全局停止 | `CrawlSession.shouldContinue` | 概览页第一屏就要显示 `remainingPages` 和熔断进度 |

## 3. 设计原则

1. **只读优先**：P0 不引入任何写路径，先把「看得见」做扎实；控制能力单独一期，配二次确认。
2. **不伪造数据**：数据库里没有的东西（失败原因、处理时间）就不在界面上编，宁可显示「未记录」。缺数据的部分明确列为后端待补项。
3. **一次运行一个页面**：UI 的上下文是「一次 crawl run」，不是一个长期在线的服务状态。
4. **速率是护栏，不是卖点**：界面上不提供「无限制」选项，不做夸张的并发滑块。
5. **不改变现有 CLI 行为**：`Main` 继续可用，Web 只是另一种入口，共用同一套 `CrawlSession` 与 DAO。

## 4. 信息架构

```
                    ┌──────────────────────────────┐
                    │            概览               │
                    │  运行状态 / 实时计数 / 曲线    │
                    └───────────┬──────────────────┘
                                │
        ┌───────────────┬───────┴────────┬───────────────┬─────────────┐
        │               │                │               │             │
   启动 / 停止       链接队列          新闻数据          日志         环境自检
   (P1)             (P0)              (P0)             (P0)          (P0)
        │               │                │               │             │
   运行历史         失败链接          搜索 (P2)       日志下载      配置来源
   (P1)             (P2)             规则预览 (P2)
```

路由建议：

| 路由 | 页面 | 优先级 |
| --- | --- | --- |
| `/` | 概览：当前运行状态与实时指标 | P0 |
| `/links` | 链接队列：待处理 / 已处理 / 重复 / 死链 | P0 |
| `/news` | 新闻列表与详情 | P0 |
| `/logs` | 日志查看 | P0 |
| `/settings` | 环境自检与生效配置 | P0 |
| `/runs` | 运行历史 | P1 |
| `/search` | 全文检索 | P2 |

## 5. 页面详述

### 5.1 概览（P0）

第一屏回答「现在怎么样」，第二屏回答「趋势如何」。

**状态区**：状态徽标 + 起止时间 + 已运行时长。状态机：

```
IDLE ──start──> STARTING ──> RUNNING ──stop──> STOPPING ──> FINISHED
                                 │                            ▲
                                 └── 预算用完 / 熔断 ──────────┘
                                 └── 线程异常 ──> FAILED
```

结束原因单独记录，不与状态混在一起：

| stopReason | 含义 |
| --- | --- |
| `COMPLETED` | 队列取空，所有线程正常退出 |
| `BUDGET_EXHAUSTED` | `maxPages` 用完 |
| `CIRCUIT_TRIPPED` | 连续被拦达到阈值 |
| `INTERRUPTED` | 用户手动停止 |
| `FAILED` | 线程抛出未捕获异常（多半是数据库错误） |

**计数区**（均来自 `CrawlSession` 的快照）：

- 进度：`attemptedPages / maxPages`，附 `remainingPages`
- 产出：`storedNews`（抓到并入库的新闻数）
- 丢弃：`droppedLinks`（重试耗尽仍失败的链接数）
- 熔断：`consecutiveBlocks / threshold`，用进度条呈现，接近阈值时变黄/红
- 速率：生效的 `requestsPerSecond` 与实际观测到的「页/秒」并排显示，用来验证限速是否生效

**趋势区**：两条时序曲线 + 一个分布。

- 每秒抓取页数、每秒入库新闻数（滑动窗口，建议 10s/1min 两档）
- HTTP 状态码分布：200 / 404·410 / 403·429·503 / 超时·连接失败
- 状态码分布是判断「被限流」还是「链接本身已死」的主要依据

**结束摘要**：运行结束时，把 `Main#logSummary` 现在打印的那段内容和 stopReason 一起固定展示，并链接到运行历史。

### 5.2 链接队列（P0）

三个区块，对应三张表的实际语义：

**待处理（`links_to_be_processed`）**

- 总量、按域名分组计数、最近入队的样本（表里没有时间列，只能按 `id` 倒序取）
- 重复 URL Top N：`group by link having count(*) > 1`，这是「入队不去重」的直接证据，值得单独暴露
- 操作：删除单条、清空（危险操作，需二次确认并说明后果）

**已处理（`links_already_processed`）**

- 按 URL 精确/前缀搜索，回答「这条链接抓过没有」
- 总量；注意这张表没有时间列，**不要**做「按时间排序」这种界面上有、数据上无的功能

**死链与失败**

- 死链：目前与已处理混在一起，只能显示「已标记为已处理」。若要分类展示，需先补状态列（见 6.5）
- 失败链接：数据尚未持久化，P2 接入 `failed_links` 后再提供「失败清单 + 重新入队」

### 5.3 新闻数据（P0）

- 列表列：标题、URL、创建时间、修改时间、正文字数
- 筛选：标题关键字（`like`，注意全表扫描的成本，需限制最小长度）、时间范围
- 详情：抽屉或独立页，展示完整 title / content / url / 时间戳，提供「打开原文」外链
- 分页：**键集分页**（`where id < ? order by id desc limit ?`），禁止 `offset`
- 重复检测：同一 `url` 出现多行时标红。`news` 没有唯一约束，重复入库在理论上可能发生

### 5.4 日志（P0）

- `tail` 方式读取 `logs/crawler.log`（滚动文件按 `crawler.%d.%i.log.gz` 命名，需要能跨文件续读）
- 按级别过滤（INFO / WARN / ERROR）、按关键字搜索、按线程名过滤（`crawler-0`…）
- 实时模式走 SSE；历史模式按行号区间分页拉取，避免一次性把 10MB 文件塞给浏览器
- 下载当前日志文件（注意先做脱敏，日志里可能出现完整 URL 与数据库错误信息）

### 5.5 环境自检（P0）

一页把「环境到底通不通」讲清楚：

- MySQL：连接结果、当前库名与主机（**不显示密码**）、`select version()`
- Flyway：当前 schema 版本（查 `flyway_schema_history` 最新一行）
- Elasticsearch：`ping` 结果、集群名、`news` 索引文档数、`http_ca.crt` 是否存在
- 配置：`crawler.*` 六个参数的**生效值与来源**（系统属性 / 环境变量 / local.properties / 默认值）

最后一项是这个项目最容易被坑的地方：`CrawlConfig#read` 的优先级是 系统属性 → 环境变量 → local.properties → 默认值，
界面上写清「当前值来自哪里」，能省掉大量「我明明改了怎么没生效」的排查。

### 5.6 启动与停止（P1）

**启动表单**：六个字段，默认值取自 `CrawlConfig.load()`，校验规则与 `CrawlConfig` 构造器逐条对齐：

| 字段 | 校验 | 说明 |
| --- | --- | --- |
| `crawler.threads` | 整数 ≥ 1 | 只影响并发掩盖延迟，不影响速率 |
| `crawler.ratePerSecond` | > 0 | 全局限速，界面上限建议 5，默认 1 |
| `crawler.maxPages` | 整数 ≥ 1 | 单次运行页数预算 |
| `crawler.maxRetries` | 整数 ≥ 0 | 重试次数，不含首次 |
| `crawler.retryBackoffMillis` | ≥ 0 | 指数退避基数，代码里另有 2 分钟上限 |
| `crawler.circuitBreakerThreshold` | 整数 ≥ 1 | 连续被拦多少次触发熔断 |

交互要求：

- 提供「保守（1 req/s, 3 线程, 500 页）」「标准」两个预设，默认选中保守
- 启动前二次确认，文案里写明目标站点与「会产生真实请求并写入数据库」
- 同一时刻只允许一个运行；已有运行时按钮置灰并显示当前运行
- 停止 = 优雅停止：interrupt 所有 worker 线程 + `join` + 关闭 `CrawlSession`，UI 显示 `STOPPING` 直到线程真正结束

### 5.7 运行历史（P1）

每次运行一行：开始/结束时间、时长、参数快照、`attemptedPages` / `storedNews` / `droppedLinks`、stopReason。
点开看该次运行的完整参数与结束摘要。

这需要新增 `crawl_runs` 表（见 6.3），因为现在汇总只 `LOG.info` 一次。

### 5.8 搜索（P2）

- 输入关键字，对 ES `news` 索引的 `title` 做 `match` 查询，与 `ElasticsearchEngine#searchTitle` 行为一致
- 结果展示标题、片段高亮、时间、原文链接；分页
- ES 不可用时明确提示「搜索服务不可用」，并给出去 `/settings` 检查的入口，不要返回 500 空白页
- 客户端必须复用（见 8.4），不能每个请求新建 transport

### 5.9 链接规则预览（P2）

输入一个 URL，输出「会不会被抓」以及**命中了哪一条规则**：

- 通过：`sina.cn` + 未被排除 + 非 `javascript` + 非 `.php`
- 拒绝：具体哪一条拒绝，例如「包含 `edu.sina.cn`」

实现前提是把 `Crawler#isRequiredLink` 及其组合的私有方法抽成独立的 `LinkPolicy`，返回「结果 + 原因」。
这同时能让现有过滤规则变得可测试，属于顺手改进。

## 6. 后端需要先补的能力

这是本设计的真实成本，UI 本身反而是小头。

### 6.1 RunManager

把 `Main` 里「建 session → 爬首页 → 起线程 → join → 打汇总」抽成 Web 可调用的组件：

```java
public final class RunManager {
    RunState state();                 // IDLE / STARTING / RUNNING / STOPPING / FINISHED / FAILED
    RunSnapshot start(CrawlConfig c); // 已有运行在跑时抛 IllegalStateException
    void stop();                      // interrupt + join，幂等
    RunSnapshot current();            // 计数 + 状态 + 起止时间 + stopReason
}
```

约束：

- 全局单例，内部用一把锁保护状态迁移，保证不会并发启动两次
- 所有线程结束后负责 `session.close()`，并把结果写入 `crawl_runs`
- 停止流程必须调用 `interrupt()`，因为 worker 在退避期间是 `Thread.sleep`

### 6.2 运行时埋点

现在只有全局计数，没有「每个线程正在做什么」。实时监控要补齐以下只读快照：

- 线程名、存活状态、当前 URL、当前第几次尝试、是否处于退避等待

实现方式二选一：给 `Crawler` 增加 `volatile` 字段供读取，或发结构化事件（推荐后者，日志与 SSE 可复用）。
注意不要为此加锁，否则会把监控变成吞吐瓶颈。

### 6.3 `crawl_runs` 表（Flyway V3）

```sql
create table crawl_runs
(
    id                        bigint primary key auto_increment,
    started_at                timestamp    not null default current_timestamp,
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
```

参数必须存快照，而不是外键指向配置：配置会变，历史不能变。

### 6.4 `failed_links` 表（P2）

```sql
create table failed_links
(
    id          bigint primary key auto_increment,
    run_id      bigint       not null,
    link        varchar(4096),
    status_code int,
    outcome     varchar(32),
    attempts    int,
    created_at  timestamp default current_timestamp,
    key idx_link (link(191)),
    key idx_run (run_id)
) default charset=utf8mb4;
```

写入点：`processLink` 的 `default` 分支（被拦 / 网络错误重试耗尽）以及 3 次重试仍失败的情况。
注意保持现有语义：**失败链接不得写入 `links_already_processed`**，否则等于永久丢弃。

### 6.5 死链状态列（可选）

给 `links_already_processed` 加 `reason varchar(32)`（`OK` / `DEAD` / `SKIPPED`），或单独建 `dead_links`。
不加也能上线，只是队列页无法区分「抓过」与「抓过但已死」。

### 6.6 索引补充（Flyway V3）

```sql
create index idx_news_created_at on news (created_at);
create index idx_news_title on news (title(191));
```

`title` 是 `text`，只能建前缀索引；它只用于「按标题筛选」的粗筛，正儿八经的全文检索仍走 ES。

### 6.7 链接过滤器抽取

`LinkPolicy` 提供 `Decision check(String link)`，`Decision` 含 `accepted` 与 `reason`。
`Crawler#isRequiredLink` 改为委托给它，行为不变，规则可测、可预览。

## 7. API 草案

统一前缀 `/api`，JSON，错误返回 `{"message": "..."}`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/runs/current` | 当前运行快照；`state=IDLE` 时返回空对象 |
| POST | `/api/runs` | 启动，body 为六个参数；已有运行返回 409 |
| POST | `/api/runs/{id}/stop` | 优雅停止，幂等 |
| GET | `/api/runs` | 运行历史分页 |
| GET | `/api/runs/{id}` | 单次运行详情 |
| GET | `/api/links/pending?page=` | 待处理列表 |
| GET | `/api/links/pending/summary` | 总量 + 域名分布 + 重复 Top N |
| DELETE | `/api/links/pending/{id}` | 删除一条待处理链接 |
| GET | `/api/links/processed?q=&page=` | 已处理查询 |
| GET | `/api/news?beforeId=&title=&from=&to=` | 新闻列表（键集分页） |
| GET | `/api/news/{id}` | 新闻详情 |
| GET | `/api/search?q=&page=` | ES 检索 |
| GET | `/api/health` | DB / ES / 证书 / schema 版本 |
| GET | `/api/config/effective` | 生效配置 + 来源（脱敏） |
| GET | `/api/logs?sinceId=&level=&q=` | 历史日志分页 |
| GET | `/api/logs/stream` | SSE 实时日志与计数 |

SSE 事件格式：

```
event: tick
data: {"state":"RUNNING","attemptedPages":123,"remainingPages":377,"storedNews":98,"droppedLinks":4,"consecutiveBlocks":2,"elapsedMillis":184000}

event: log
data: {"ts":"2026-10-20T22:11:20.123+08:00","level":"WARN","thread":"crawler-1","message":"blocked with status 429 ..."}

event: finished
data: {"stopReason":"CIRCUIT_TRIPPED","attemptedPages":210,"storedNews":160,"droppedLinks":50}
```

计数事件建议 1s 一次并做节流；日志事件按行推送但需要限速，避免爬虫高频日志把浏览器压垮。

## 8. 关键技术决策

### 8.1 部署形态：必须单 JVM

`getNextLinkThenDelete` 的 `synchronized` 只在同一 JVM 内有效。若 Web 应用与爬虫分属两个进程并都操作队列表，
「不重复抓取」的保证就失效了。

**结论**：Web 层与爬虫同进程部署，共用 `RunManager` 与 `MyBatisCrawlerDao` 实例。
不做多实例部署；如果将来要横向扩展，必须先把出队原子性改成 `DELETE ... LIMIT 1` 或 `SELECT ... FOR UPDATE`。

### 8.2 实时推送

选 SSE 而不是 WebSocket：单向、文本、自动重连、能直接复用现有的日志行格式，不需要引入额外协议。

### 8.3 分页与计数

- 新闻列表、日志一律键集分页
- `count(*)` 在 100 万行 / 1600 万文档的量级下不可接受，做 30s 缓存或使用近似值，并在界面上标注「约」

### 8.4 ES 客户端

`ElasticsearchEngine` 每输入一个关键字就新建客户端再关闭 transport，这是 CLI 用完即走的写法。
Web 端必须做单例复用，关停时统一 `close()`。同时 ES 口令目前硬编码在 `ElasticsearchUtil`，不能透出到前端。

### 8.5 技术选型

Java 8 这个约束把选项压窄了：Spring Boot 3 需要 Java 17。

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| Spring Boot 2.7 + Thymeleaf/htmx + SSE（推荐） | 一个 jar 起服务，无 Node 构建链，生态成熟 | 引入较大依赖树，需注意与 Java 8 的兼容 |
| 独立只读 admin 模块（Servlet + 少量页面） | 完全不动 `Main`，风险最低 | 控制能力要另做，代码偏手工 |
| 前端 SPA + 单独后端 | 交互体验好 | 引入 Node/打包链，与当前仓库的简洁风格冲突 |

建议先按第二种做 P0（只读），控制能力再引入 Spring Boot 或直接扩展该模块。无论哪种，都不应改动 `pom.xml`
的编译级别，也不要使用 Java 8 以上的 API。

## 9. 安全红线

- **凭据不出服务端**：DB 口令、ES 口令一律只显示「已配置」，接口返回中不得包含明文
- **不做任意 URL 抓取代理**：规则预览只做字符串判断，不能真的发起请求，否则就是 SSRF
- **不暴露 `MockDataGenerator`**：它会往真实库补到 100 万条，绝不能做成按钮
- **限速有上限**：UI 侧对 `ratePerSecond` 设硬上限（建议 5），不能只在文档里劝
- **危险操作二次确认**：启动、清空队列、删除链接
- **日志脱敏**：下载日志前过滤数据库连接串等敏感片段
- **默认只监听回环地址**：`127.0.0.1`，需要远程访问时再显式配置并加一层认证

## 10. 分期计划

| 阶段 | 内容 | 风险 | 验收 |
| --- | --- | --- | --- |
| P0 | 概览（只读快照）、链接队列、新闻浏览、日志查看、环境自检 | 极低，无写路径 | 不改动爬虫逻辑，`mvn verify` 通过 |
| P1 | RunManager、启动/停止、实时埋点与 SSE、运行历史、`crawl_runs` 表 | 中，涉及并发与状态迁移 | 起停可重复、停止后线程确实退出、历史与日志一致 |
| P2 | ES 搜索、链接规则预览、失败链接清单与重试、死链状态 | 中 | 失败链接不入已处理表；ES 故障时页面降级而非 500 |

## 11. 未决问题

1. 是否需要多用户 / 登录？当前定位是本地单用户工具，P0/P1 默认不做认证，仅监听 `127.0.0.1`。
2. 日志实时推送与 `logs/crawler.log` 的滚动切割如何衔接：是 tail 文件，还是在 Logback 里加一个内存 appender？后者更可靠但会改日志配置。
3. 失败链接的自动重试是否要限次？目前库里没有任何重试计数字段，需要和 `failed_links` 一起设计。
4. 是否需要「定时运行」（cron）？这会引入进程长期驻留、运行重叠、失败告警等一整套问题，建议明确排除在 P0–P2 之外。

## 12. 明确不做的事情

- 不做分布式调度、多节点抓取
- 不做实时新闻流 / 推送订阅
- 不做数据可视化的大屏
- 不改抓取速率语义，不把线程数当作速率开关
- 不把 CLI 入口删掉或改造，`Main` 保持可用

## 13. P0 实现说明

P0 已实现，入口是 `com.github.magicalmuggle.web.AdminMain`，默认监听 `127.0.0.1:8080`。
它与 `Main` 完全独立：不启动爬虫、不写数据库，只读 MySQL 和 `logs/crawler.log`。

### 13.1 代码位置

| 文件 | 作用 |
| --- | --- |
| `web/AdminMain.java` | 入口：解析 `--port` / `--bind`，注册关停钩子 |
| `web/AdminServer.java` | JDK 自带 `HttpServer`：路由、静态文件、错误处理、8 个工作线程 |
| `web/ApiRoutes.java` | 所有只读 API |
| `web/AdminDao.java` | 只读查询，`SqlSessionFactory` 只构造一次 |
| `web/AdminMapper.java` + `db/mybatis/AdminMapper.xml` | 只读 SQL，与爬虫的 `MyMapper` 完全分开 |
| `web/NewsSummary.java` | 列表视图：不带正文，只带 `contentLength` |
| `web/HealthChecker.java` | DB / ES / 证书 / Flyway 自检，结果缓存 5 秒，ES 探测 4 秒超时 |
| `web/LogTailer.java` + `web/LogEntry.java` | 日志尾部解析、按级别/关键字过滤、SSE 增量推送 |
| `web/ConfigReport.java` | `crawler.*` 生效值与来源 |
| `src/main/resources/web/` | `index.html` / `app.js` / `style.css`，无构建步骤 |

### 13.2 与设计稿的差异

1. **概览没有实时运行指标**。P0 不引入 RunManager，Web 进程看不到另一个进程里的 `CrawlSession`，
   所以概览改为「数据视角」：新闻总数、今日新增、队列积压、最近入库时间、最近日志，
   页面顶部有明确说明。实时进度、熔断状态、线程级 URL 属于 P1。
2. **新闻列表用 `NewsSummary`**。列表不带正文，只带 `contentLength`；正文只在详情接口返回。
   否则一页 50 条会把整篇文章都传给浏览器（实测单条正文可达数千字符）。
3. **日志历史简化为「最近 N 行 + 过滤」**。没有做行号区间分页；`/api/logs` 从文件尾部最多读 2 MB。
4. **队列页没有删除 / 清空**。P0 零写路径，这些操作留到后续版本并需要二次确认。
5. **健康检查缓存 5 秒**，避免每次刷新页面都去 ping ES。
6. **`News` 增加了 `id` 字段**，详情接口需要；对 ES 文档没有影响（该字段为空，不会进入 `_source` 语义）。

### 13.3 校验方式

- `mvn test`：29 个测试。`AdminServerTest` 用 H2 + JDK `HttpServer` 做端到端 HTTP 验证
  （概览、列表、详情、404/405、静态文件、健康检查、SSE 日志推送），`AdminDaoTest` 覆盖只读 SQL。
- 真实 MySQL 冒烟（手工执行、未提交测试代码）：`/api/overview`、`/api/news`、`/api/links/pending`、
  `/api/health` 均返回 200；新闻列表不再携带正文；`char_length` 与 `limit ?` 在 MySQL 8.4 上工作正常。
- `mvn clean verify` 需要在 JDK 8 下运行（SpotBugs 4.5.3 的限制，见 AGENTS.md），
  已通过：29 个测试 + Checkstyle + SpotBugs。

## 14. P1 实现说明

P1 已完成：在同一个进程内启动/停止抓取、实时指标、运行历史。入口仍然是 `AdminMain`，
但它只有在你于「运行」页点击「开始抓取」时才真正访问新浪，启动服务本身不会抓取。

### 14.1 新增的组件

| 文件 | 作用 |
| --- | --- |
| `web/RunManager.java` | 单例运行管理器：状态机、起停、快照、历史读写 |
| `web/RunState.java` / `web/StopReason.java` | 运行状态与结束原因（分开记录） |
| `web/RunSnapshot.java` | 当前运行的只读快照，`/api/runs/current` 与 SSE 返回它 |
| `web/CrawlLauncher.java` / `web/CrawlExecution.java` | 把「怎么抓」与「怎么管状态」解耦，便于测试 |
| `web/DefaultCrawlLauncher.java` | 真实实现：复用 `Main` 的流程（首页 → N 个 worker → join） |
| `web/RunDao.java` / `web/RunMapper.java` / `web/RunRecord.java` | `crawl_runs` 的持久化 |
| `db/mybatis/RunMapper.xml` | `crawl_runs` 的增改查 |
| `db/migration/V3__Create_crawl_runs.sql` | 运行历史表 |
| `CrawlMonitor.java` | 每个 worker 的当前链接、尝试次数、阶段（fetching / waiting / idle） |
| `CrawlSession#requestStop` | 停止开关；`shouldContinue()` 会因此返回 false |

### 14.2 运行语义

- **同一时刻只允许一次运行**：`RunManager.start` 在锁内检查 `IDLE / FINISHED / FAILED`，
  否则抛 `IllegalStateException`，API 映射成 409。
- **停止是两段式的**：先 `CrawlSession.requestStop()` 让循环在下一轮自然退出，
  再 `interrupt()` 唤醒正在退避 sleep 的线程。已经在途的 HTTP 请求不会被强杀，
  最长等待一个请求超时（连接 5s / 读取 10s）。
- **状态与原因分开**：`state` 是 `IDLE / STARTING / RUNNING / STOPPING / FINISHED / FAILED`，
  `stopReason` 是 `COMPLETED / BUDGET_EXHAUSTED / CIRCUIT_TRIPPED / INTERRUPTED / FAILED`。
- **历史只在开始和结束各写一次**：开始写参数快照，结束写计数与原因。进程崩溃会导致
  `finished_at` 为空，页面显示「未正常结束」——这是有意的取舍，避免每秒写库。

### 14.3 新增 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/runs/current` | 当前运行快照（IDLE 时 `config` 为 null） |
| GET | `/api/runs/stream` | SSE，每秒一帧 `tick` |
| GET | `/api/runs?limit=` | 运行历史；表缺失时返回 `available:false` |
| GET | `/api/runs/{id}` | 单次运行详情 |
| POST | `/api/runs` | 启动；请求体是六个参数，缺省取 `CrawlConfig.load()` |
| POST | `/api/runs/{id}/stop` | 停止当前运行 |
| GET | `/api/config/effective` | `crawler.*` 生效值与来源（启动表单的默认值） |

界面硬上限写在 API 里，不只是前端校验：线程数 ≤ 8、`ratePerSecond` ≤ 5。
其余参数沿用 `CrawlConfig` 构造器的校验，非法值返回 400。

### 14.4 与设计稿的差异

1. **历史不做周期性更新**，只在开始/结束写两次（见 14.2）。
2. **表缺失不阻塞抓取**：`crawl_runs` 不存在时运行照常进行，只是 `runId` 为 null、历史页提示
   先执行 `mvn flyway:migrate`。
3. **运行页的 SSE 是常驻连接**：进入页面即连接，离开时关闭；服务端每帧只发快照，不发增量。
4. **停止按钮按当前快照的 `runId` 调用**；若历史表不可用（`runId` 为 null）则用 0 占位，
   服务端只校验「有活跃运行」。
5. **`/api/overview` 增加 `run` 字段**，概览页据此显示「当前有一次抓取」的横幅。

### 14.5 校验方式

- `mvn test`：37 个测试。新增 `RunManagerTest`（起停、单运行约束、历史表缺失仍可运行）、
  `RunDaoTest`（H2 上的增改查）、`AdminServerTest` 的 run 端点与 SSE。
- `mvn clean verify`（JDK 8）通过：37 测试 + Checkstyle + SpotBugs。
- **没有对真实站点执行抓取**：所有运行相关测试都用 `CrawlLauncher` 替身，
  不会发出 HTTP 请求，也不会写 `news` 表。
- 运行前需要先执行 `mvn flyway:migrate` 创建 `crawl_runs`（V3）。

## 15. P2 实现说明

P2 补齐了检索与排障相关的能力：ES 搜索、链接规则预览、失败链接重试、死链状态。

### 15.1 新增组件

| 文件 | 作用 |
| --- | --- |
| `LinkPolicy.java` | 链接过滤规则的唯一实现，返回「是否接受 + 原因」 |
| `web/SearchService.java` / `web/ElasticsearchSearchService.java` | 搜索抽象与 ES 实现（懒加载、复用客户端） |
| `web/SearchResult.java` / `web/SearchHit.java` | 一页搜索结果 |
| `web/FailedLink.java` / `web/FailedLinkMapper.java` / `web/FailedLinkDao.java` | 失败链接的读取与重新入队 |
| `db/migration/V4__Add_reason_and_failed_links.sql` | `links_already_processed.reason` + `failed_links` |
| `db/mybatis/FailedLinkMapper.xml` | 失败链接 SQL |

### 15.2 语义

- **失败链接**：被拦（403/429/503）或网络错误在重试耗尽后写入 `failed_links`，
  仍然**不写** `links_already_processed`；手动停止导致的提前退出既不算失败，也不计入 `droppedLinks`。
- **重新入队**是事务操作：`insert into links_to_be_processed` + `delete from failed_links` 同一个 session，
  失败则回滚。
- **死链**：`links_already_processed.reason` 记录 `OK` / `DEAD` / `SKIPPED`，界面据此区分。
- **规则预览**只做字符串判断，不会发起任何请求（避免 SSRF）。

### 15.3 新增 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/search?q=&page=&size=` | ES 的 title match 查询；ES 不可用返回 503 |
| GET | `/api/links/check?url=` | 返回 `{accepted, reason}` |
| GET | `/api/links/failed?limit=` | 失败链接列表 + 总数，表缺失返回 503 |
| POST | `/api/links/failed/{id}/requeue` | 放回待处理队列 |

### 15.4 与设计稿的差异

1. **搜索不做高亮**，只返回 `_score` 与摘要；正文在写入 ES 前已被裁剪到 10 个字符，摘要意义有限。
2. **失败链接不自动重试**，只提供手动「重新入队」，避免在被限流时自动放大请求。
3. **规则预览是纯字符串判断**，与 `Crawler.isRequiredLink` 共用 `LinkPolicy`，不会出现两套规则。
4. **`failed_links` 不记录尝试次数**：重试次数由运行参数决定，失败表只保留状态码与结果。

### 15.5 校验方式

- `mvn test`：45 个测试。新增 `LinkPolicyTest`（规则边界）、`FailedLinkDaoTest`（重新入队事务）、
  `CrawlerBackoffTest` 对 `failed_links` 与 `DEAD` 原因的断言，`AdminServerTest` 覆盖搜索 / 规则 / 失败链接接口。
- 搜索用 `SearchService` 替身测试，真实 Elasticsearch 未在本环境联调；ES 不可用时的 503 降级路径由接口测试覆盖。
- `mvn clean verify`（JDK 8）通过：45 测试 + Checkstyle + SpotBugs。
- 需要先执行 `mvn flyway:migrate` 应用 V4（`reason` 列与 `failed_links` 表）。
