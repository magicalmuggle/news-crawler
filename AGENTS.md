# AGENTS.md

本文件是 AI 编码代理（Claude Code、Codex、DeepSeek Harness 等）在本仓库工作时的指令。
面向人类的使用说明见 [README.md](README.md) / [README_CN.md](README_CN.md)。

## 1. 项目概览

多线程新闻爬虫：抓取新浪新闻（sina.cn）→ MySQL → 同步到 Elasticsearch 提供全文搜索。
纯后端 CLI 项目，没有 Web 层，每个功能都是独立的 `main` 入口。

| 项目 | 值 |
| --- | --- |
| 语言 / 构建 | Java 8（pom 中 `source`/`target` = 1.8）、Maven 单模块 |
| 数据访问 | MyBatis 3.5.9 + MySQL 8（`mysql-connector-java` 8.0.29） |
| 建表 | Flyway 8.5.10（Maven 插件） |
| HTTP / 解析 | Apache HttpClient 5.1.3、Jsoup 1.14.3 |
| 搜索 | Elasticsearch Java Client 8.2.1（HTTPS + CA 证书 + Basic Auth） |
| 测试 | JUnit 5（junit-jupiter 5.8.2）、Surefire 2.22.1 |
| 质量门禁 | Checkstyle 8.29、SpotBugs 4.5.3.0，均在 `verify` 阶段执行 |
| CI | CircleCI：`mvn clean verify -PmavenCentral` |

## 2. 构建、测试与校验

```bash
mvn install                 # 编译 + 打包 + 安装到本地仓库
mvn test                    # 运行全部测试
mvn test -Dtest=SmokeTest   # 运行单个测试类
mvn verify                  # 测试 + checkstyle + spotbugs（提交前的底线）
mvn checkstyle:check        # 只跑风格检查
mvn spotbugs:check          # 只跑静态分析
mvn flyway:migrate          # 执行数据库迁移（需要可用的 MySQL）
```

- **CI 等价命令**：`mvn clean verify -PmavenCentral`。默认 profile 使用阿里云 Maven 镜像；
  镜像不可用或需要复现 CI 时加 `-PmavenCentral`。
- **`verify` 可能因环境失败**：checkstyle / spotbugs 插件依赖较多，本地 `~/.m2` 未缓存完整依赖
  （或代理环境无权限写入 `~/.m2`）时，`verify` 会在**插件解析阶段**失败，此时 `mvn -o test`
  仍可验证编译与测试。看到 `PluginResolutionException` / `Permission denied` 先判断是不是这个原因，
  不要误以为代码有问题。
- **JDK 版本**：本机默认 JDK 21，能编译 `-source 8`（会有 obsolete 警告），但源码里**不要使用
  Java 8 以上的 API**（如 `var`、`List.of`、`Stream.toList`、新日期 API 之外的新类）。
- **运行入口**：仓库没有配置 `exec-maven-plugin` / `shade`，请直接在 IDE 中运行对应 `main` 类；
  不要为了跑程序往 `pom.xml` 里加插件。
- 后台任务 / 长输出较多是常态：`MockDataGenerator` 会刷一百万行进度，`verify` 输出也很长，
  用 `| tail` 之类的方式截断，不要整段回显。

### 入口点

| 类 | 作用 |
| --- | --- |
| `com.github.magicalmuggle.Main` | 启动爬虫：先爬首页，再开 `CPU 核数 × 2` 个线程 |
| `com.github.magicalmuggle.ElasticsearchEngine` | 交互式搜索：循环读 stdin，按 `title` 做 match 查询 |
| `com.github.magicalmuggle.ElasticsearchDataGenerator` | 把 MySQL 数据批量写入 Elasticsearch（16 线程 × 1000 次 bulk） |
| `com.github.magicalmuggle.MockDataGenerator` | 生成/补足 100 万条模拟新闻（会写真实库，慎用） |

## 3. 目录结构

```
src/main/java/com/github/magicalmuggle/
├── Main.java                       # 爬虫入口，管理线程数
├── Crawler.java                    # 爬虫线程：下载、解析、链接过滤、新闻抽取
├── CrawlerDao.java                 # 数据访问接口
├── MyBatisCrawlerDao.java          # 当前使用的 DAO 实现
├── JdbcCrawlerDao.java             # 旧的 JDBC 实现，Main 已不使用
├── MyBatisUtil.java                # SqlSessionFactory 工厂（供 Mock / ES 生成器使用）
├── MyMapper.java / MockMapper.java # MyBatis Mapper 接口
├── News.java                       # 实体：url/title/content/createdAt/modifiedAt
├── ElasticsearchUtil.java          # ES 客户端构造（HTTPS、证书、认证）
├── ElasticsearchEngine.java        # 搜索 CLI
├── ElasticsearchDataGenerator.java # MySQL → Elasticsearch
└── MockDataGenerator.java          # 模拟数据生成

src/main/resources/db/migration/V1__Create_tables.sql   # 三张表的建表脚本
src/main/resources/db/mybatis/config.xml                # 数据源 + Mapper 注册
src/main/resources/db/mybatis/MyMapper.xml              # 爬虫用 SQL
src/main/resources/db/mybatis/MockMapper.xml            # 模拟数据 / 导出用 SQL
src/test/java/com/github/magicalmuggle/SmokeTest.java   # 唯一的测试，目前为空实现
.circleci/checkstyle.xml                                # Checkstyle 规则源文件
```

## 4. 运行时架构

```
sina.cn ──HTTP──> Crawler（多个线程）
                      │  MyBatis
                      ▼
                   MySQL（news / links_to_be_processed / links_already_processed）
                      │  ElasticsearchDataGenerator
                      ▼
                Elasticsearch（news 索引） <── ElasticsearchEngine（stdin 搜索）
```

### 爬取循环（`Crawler.run`）

1. `dao.getNextLinkThenDelete()`：原子地「取一条待处理链接并删除」，返回 `null` 时线程退出。
2. `dao.isLinkProcessed(link)`：已在 `links_already_processed` 中则跳过。
3. `httpGetAndParseHTML`：HttpClient 5 + 伪装 UA；`cleanUrl` 会去掉 URL 中的 `{` `}`。
4. `parseUrlsFromPageAndStoreIntoDatabase`：取所有 `<a>`，过滤后入队；`//` 开头补 `https:`，并删除空白字符。
5. `storeIntoDatabaseIfItIsNewsPage`：页面存在 `<article>`，且第一个子节点文本作 title、
   所有 `<p>` 拼接作 content，两者都非空才写 `news` 表。
6. `dao.insertLinkAlreadyProcessed(link)`：标记已处理。

线程内所有受检异常统一包成 `RuntimeException` 抛出，**线程会直接终止**；排查时先看第一条堆栈。

### 链接过滤规则（`Crawler.isRequiredLink`）

必须同时满足：

- 包含 `sina.cn`；
- 不包含以下子域名：`passport.sina.cn`、`edu.sina.cn`、`auto.sina.cn`、`gu.sina.cn`、
  `ts.gd.sina.cn`、`travel.sina.cn`；
- 不以 `javascript` 开头（大小写不敏感）；
- 不包含 `.php`。

### 多线程

- 线程数 = `Runtime.getRuntime().availableProcessors() * 2`。
- 多线程启动前先同步爬一次首页，避免待处理链接太少导致线程空转退出。
- `MyBatisCrawlerDao.getNextLinkThenDelete()` 是 `synchronized` 的，这是取链接不重复的唯一保证；
  改动它（包括改成异步、去掉同步）会直接引入重复抓取。
- 除只读查询外，每个写操作单独 `openSession(true)`（自动提交），没有跨方法事务。

## 5. 数据层约定

三张表（见 `V1__Create_tables.sql`）：`news`、`links_to_be_processed`、`links_already_processed`。

`CrawlerDao` 的 5 个方法：`getNextLinkThenDelete`、`insertNewsIntoDatabase`、`isLinkProcessed`、
`insertLinkToBeProcessed`、`insertLinkAlreadyProcessed`。

- **改表结构**：新增 `V2__xxx.sql` 之类的迁移文件，**不要修改已应用过的 `V1`**。
- **命名映射**：`config.xml` 开启 `mapUnderscoreToCamelCase`，所以 `created_at` ↔ `createdAt`。
- **新增 DAO 方法需要同步四处**：`CrawlerDao` 接口、`MyBatisCrawlerDao` 实现、
  `MyMapper` 接口、`MyMapper.xml` 中的 SQL。漏掉任何一处都会在运行时才报错。
- `MyMapper.insertLink` 用 `${tableName}` 拼接表名（MyBatis 的字符串替换）。调用方只应传内部常量
  （`links_to_be_processed` / `links_already_processed`），**绝不要传外部输入**（SQL 注入）。
- `MockMapper.selectNews` 固定 `limit 1000`；`ElasticsearchDataGenerator` 会把这个结果集
  重复写入 1000 次，所以「1000 条 × 16 线程 × 1000 批」≈ 1600 万文档，别在生产 ES 上试。
- 测试现状：`SmokeTest` 是空测试；H2 依赖在 `pom.xml` 里，但 `config.xml` 指向真实 MySQL，
  H2 **不会**自动生效。要写数据库相关测试，需要为测试单独提供数据源/建表脚本。

## 6. Elasticsearch 相关

- 连接参数硬编码在 `ElasticsearchUtil`：`https://localhost:9200`、用户 `elastic`、密码常量、
  CA 证书 `./http_ca.crt`（该文件被 `.gitignore` 忽略，需要从 ES 容器里 `docker cp` 出来）。
- 客户端注入 `JavaTimeModule`，`News` 的 `Instant` 字段才能正确序列化。
- `ElasticsearchEngine` 每个关键字都会新建客户端、查询后关闭 transport；这是现有模式，保持一致。
- `ElasticsearchDataGenerator` 先把内容裁剪到 10 个字符（`News.cutNewsContent`）以节省磁盘，
  这是**写 ES 前的有损操作**，不要把它挪到读 MySQL 之前。

## 7. 代码风格（会被 `verify` 拦截）

规则来源：`.circleci/checkstyle.xml`，测试源码也在检查范围内（`includeTestSourceDirectory`）。

- 4 空格缩进（全仓库约定，checkstyle 未强制缩进字数）；**Java 源码禁用 Tab**。
- 换行符 LF，文件末尾必须有换行，行尾不能有空白。
- 注意：`mvn checkstyle:check` 只扫描 `src/main/java` 与 `src/test/java`。`src/main/resources`
  下的 XML 不在扫描范围，但里面有 Tab 和缺少末尾换行的历史问题；新增/修改 XML 时请随手修正，
  不要指望 `verify` 帮你发现。
- 控制语句必须带花括号，单行 `if` 也一样。
- 不能有未使用、冗余或非法的 import；`ModifierOrder`、`RedundantModifier`、`UpperEll`、
  `LeftCurly` / `RightCurly`、`GenericWhitespace`、`WhitespaceAfter`、`NoWhitespaceBefore` 都启用。
- 没有配置行宽限制，也没有强制 Javadoc；现有代码几乎不写 Javadoc，新增代码保持同样密度即可。
- SpotBugs 在 `verify` 阶段运行。确实无法避免时用 `@SuppressFBWarnings("...")`，
  参考 `Crawler` 构造器上的 `EI_EXPOSE_REP2` 写法。
- 日志用 `System.out.println`，与现有代码一致（`slf4j-simple` 只是依赖传递，没有实际使用）。
- 中文注释可以接受，现有代码里就有。

## 8. 安全与本地环境（重要）

- **仓库里有硬编码凭据**：`src/main/resources/db/mybatis/config.xml`、`pom.xml` 的 flyway 配置、
  `ElasticsearchUtil`、`JdbcCrawlerDao`。本地开发时常被改成真实数据库地址和口令，**这类改动不要提交**。
  - 提交前用 `git diff` / `git add -p` 逐块确认，别把口令带进提交历史。
  - 新代码、新文档、示例命令里**不要**写真实口令；改到附近时优先抽成环境变量或外部配置。
- 已忽略：`target/`、`.idea/`、`.vscode/`、`*.crt`、`*.db`。
- **不要随手运行 `Main`**：它会真实访问新浪并写数据库。只用编译、测试和静态检查验证改动。
- **不要运行 `MockDataGenerator`**：它会向配置的数据库补数据到 100 万条。

## 9. 常见修改路径

| 需求 | 改动位置 |
| --- | --- |
| 新增 / 调整链接过滤规则 | `Crawler.isRequiredLink` 及其组合的私有方法 |
| 调整新闻标题 / 正文抽取 | `Crawler.storeIntoDatabaseIfItIsNewsPage` |
| 加字段 / 加表 | 新增 Flyway 脚本 + `News` + `MyMapper`/`MyMapper.xml` |
| 新增查询能力 | `CrawlerDao` / `MyBatisCrawlerDao` / `MyMapper` / `MyMapper.xml` 四处 |
| 调整搜索行为 | `ElasticsearchEngine.searchTitle`，注意索引名固定为 `news` |
| 调整线程模型 | `Main` + `MyBatisCrawlerDao.getNextLinkThenDelete` 的同步语义 |

## 10. 提交与 CI

- 历史提交以 `type(scope): subject` 为主（如 `refactor(main): ...`、`fix(http-client): ...`、
  `chore(ElasticsearchUtil): ...`），偶有大写开头。保持小写、祈使句、主题简洁即可。
- 提交前至少跑通 `mvn test`；涉及风格或新代码时跑 `mvn verify`。
- CI 会执行 `mvn clean verify -PmavenCentral`，本地通过 `-PmavenCentral` 能复现线上门禁。

## 11. 维护本文件

- 本文件是本仓库**唯一**的代理指令文档。原先的 `CLAUDE.md` 已于本文件创建后移除，其内容已被
  本文件覆盖（并修正了其中「使用 H2 做测试」等与现状不符的描述）。
- 如果有工具只认 `CLAUDE.md`（例如部分旧版 Claude Code），在 `CLAUDE.md` 里写一行
  `@AGENTS.md` 引用本文件即可，**不要**再复制一份正文，避免两份文档漂移。
- 只记录「读代码不容易得出」的信息（约定、陷阱、命令），不要复制大段实现细节。
