# Kudu 控制台

[![CI](https://github.com/999bug/kudu-crud/actions/workflows/ci.yml/badge.svg)](https://github.com/999bug/kudu-crud/actions/workflows/ci.yml)

测试环境用的 Apache Kudu 管理台：一个 Java 进程同时提供 REST 接口与 Vue 前端页面，Docker 一条命令部署。支持**多个 Kudu 平台**一键切换，每个平台独立建连、独立缓存，某个平台连不上不影响其它平台。

## 功能

| 模块 | 能力 |
| --- | --- |
| 平台管理 | 添加 / 编辑 / 删除 / 测试连接 / 切换当前平台；每个平台可单独设置 master 地址、超时、Web UI 开关与端口偏移 |
| 版本信息 | 顶栏显示应用版本与 git commit，悬停可看构建时间和镜像；`GET /api/meta/version` 也可查，升级后能直接确认服务器实际版本 |
| 集群状态 | master 列表（角色 LEADER/FOLLOWER、RPC/HTTP 地址、启动时间、版本）、tablet server 存活与心跳、clusterId、表数量；Web UI 不可达时自动降级为 RPC 侧信息 |
| 表清单 | 按库分组（Kudu 无 catalog，`db` 取自表名前缀）、搜索、行数与磁盘占用（master 统计估算）、impala 托管表标记 |
| 表结构 | 列名/类型/主键/可空/precision/scale/length/编码/压缩/默认值/注释、副本数、hash 与 range 分区、分区边界 |
| 行级 CRUD | 条件查询 + 分页、单行/批量 INSERT / UPSERT / UPDATE / DELETE，逐行错误返回 |
| 表级 DDL | 可视化建表（列定义 + hash/range 分区 + 预览）、删表、重命名、加列、删列、改副本数 |
| 删库 / 按前缀删表 | 库只是表名前缀：预览命中清单后异步逐张删除，系统表自动跳过，可取消 |
| 清空数据 | Kudu 无 truncate，用「扫描主键 + 批量 Delete」异步任务实现，带进度与取消 |
| 测试调度 | 多选表后一键删除数据 / 加列 / 添加数据，可立即执行或按固定间隔重复；用于验证备份是否捕获变更 |
| 表比对 | 单表 / 按库 / 按前缀三种模式（可跨平台）比对列结构、主键与全量行内容；报目标缺失 / 多出 / 内容不一致及样本 |
| 任务中心 | 查看各类异步任务进度并取消，任务带平台归属 |

查询条件支持 `EQ / NE / LT / LTE / GT / GTE / IN / IS_NULL / IS_NOT_NULL / PREFIX`，仅 AND 组合。

## 一键部署（Docker）

前提：一台能连通 Kudu master 的机器，装好 Docker 20.10+ 与 Compose v2。

**1. 把两个文件放到服务器 `/opt/kudu-console/`**（本仓库根目录就有）：

- `docker-compose.prod.yml`
- `.env`（复制 `.env.example` 改）

**2. 编辑 `.env`**

```env
HOST_PORT=8088
KUDU_MASTERS=192.168.46.23:7051,192.168.46.23:7151,192.168.46.23:7251
JAVA_OPTS=-Xms1024m -Xmx4g
IMAGE=ghcr.io/999bug/kudu-console:latest
TUNNEL_TOKEN=
```

- `KUDU_MASTERS` 是 Kudu 集群 master 地址，只影响首次初始化，之后以 `data/config/clusters.json` 为准
- 国内机器把 `IMAGE` 换成阿里云 ACR 地址，步骤见 **[ALIYUN-ACR.md](ALIYUN-ACR.md)**

**3. 启动**

```bash
cd /opt/kudu-console
sudo docker compose -f docker-compose.prod.yml up -d
```

**4. 验证**

```bash
curl -s http://127.0.0.1:8088/api/meta/version
```

浏览器打开 `http://<服务器IP>:8088/`：顶栏有版本号、集群状态页出现 LEADER / FOLLOWER，就部署成功了。

## 自动更新（可选）

`.env` 用 `latest` 时，挂一个 cron 每 5 分钟检查并自动换新镜像：

```cron
*/5 * * * * cd /opt/kudu-console && docker compose -f docker-compose.prod.yml pull -q && docker compose -f docker-compose.prod.yml up -d >> data/logs/auto-update.log 2>&1
```

固定 tag 不会被动更新；生产环境需要锁定版本时把 `IMAGE` 改成具体 tag。

## 更新与回滚

```bash
cd /opt/kudu-console
sudo docker compose -f docker-compose.prod.yml pull
sudo docker compose -f docker-compose.prod.yml up -d
```

- 回滚：`.env` 把 `IMAGE` 改回上一个 tag，再 `up -d`
- `data/` 是挂载卷，升级 / 回滚不丢配置和日志
- 版本是否生效：`curl -s http://127.0.0.1:8088/api/meta/version`，或看页面顶栏

## 发版方式

**push 到 main 只会跑测试，不会自动发版。** 需要出新镜像时二选一：

- **手动触发**：Actions → Release → Run workflow → 分支选 `main` → 版本号留空（自动 `v1.0.<run_number>`）或手填
- **打 tag**：`git tag v1.0.9 && git push origin v1.0.9`

流水线产出：

| 产物 | 用途 |
| --- | --- |
| `ghcr.io/999bug/kudu-console:<tag>` 与 `:latest` | Docker 部署与升级 |
| `kudu-console.jar` + `.sha256` | 直接 `java -jar`，或 systemd 部署 |
| `kudu-console-image-<tag>.tar.gz` | 完全离线时 `docker load` |
| 阿里云 ACR `<tag>` 与 `:latest` | 国内服务器拉取（需配置 Secrets，见 ALIYUN-ACR.md） |

Release 只保留最近 20 个，tag 全部保留。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [DEPLOY.md](DEPLOY.md) | 其他部署方式：jar + systemd、离线镜像包、公网访问（Cloudflare Tunnel）、源码构建、故障排查与上线检查清单 |
| [ALIYUN-ACR.md](ALIYUN-ACR.md) | 阿里云建仓、GitHub Secrets 配置、服务器拉取、自动更新的完整步骤 |
| [tools/api-smoke.py](tools/api-smoke.py) | 接口冒烟脚本（对集群建临时表再删掉） |

## 多平台

- 首次启动时，如果 `config/clusters.json` 不存在，会用 `application.yml` 里的 `kudu-console.masters` 初始化一个「默认集群」，并写入该文件。
- 之后**以 `config/clusters.json` 为准**：在「Kudu 平台」页添加、编辑、删除，重启不丢；想重新用 yml 初始化，删掉这个文件即可。
- 切换平台后，前端会给每个请求带上 `X-Kudu-Cluster: <平台id>`，后端按 id 路由到对应连接；不带该头时用服务端激活的平台（可用 `POST /api/clusters/{id}/activate` 切换）。
- 每个平台独立持有 KuduClient 与表缓存：连不上只影响该平台（返回 `KUDU_UNAVAILABLE`），其它平台照常。
- 旧的单平台配置 `config/connection.json` 会在首次启动时自动迁移成一个平台。

## 配置

`src/main/resources/application.yml`：

```yaml
server:
  port: 8088
  address: 0.0.0.0

kudu-console:
  # 仅用于首次初始化平台清单（之后以 config/clusters.json 为准）
  masters: 192.168.46.23:7051,192.168.46.23:7151,192.168.46.23:7251
  operation-timeout-ms: 30000      # 平台未单独设置时的默认值
  admin-timeout-ms: 60000
  connect-timeout-ms: 10000
  web-ui:
    enabled: true
    port-offset: 1000              # master Web UI 端口 = RPC 端口 + 1000
  query:
    default-limit: 100
    max-limit: 500
    max-offset: 10000
    max-scan-rows: 50000
  write:
    batch-size: 1000
  truncate:
    batch-size: 1000
    max-rows: 2000000
```

覆盖顺序（低 → 高）：`application.yml` → 界面里的平台配置（`config/clusters.json`）→ 命令行参数 / 环境变量：

```bat
java -jar target\kudu-console.jar --server.port=9090

set KUDUCONSOLE_MASTERS=10.0.0.1:7051    :: 只影响首次初始化
```

## 目录结构

```
kudu-console/
├─ pom.xml                     Spring Boot 3.3.12 + kudu-client 1.17.1
├─ start.bat / start.sh        一键启动
├─ build.bat / build.sh        前端构建 + 后端打包
├─ web/                        Vue 3 + Vite 源码
│  └─ src/{views,components,api.js,format.js,store.js,styles.css}
└─ src/main/
   ├─ java/com/kuducrud/
   │  ├─ config/               平台与定时任务配置持久化、每平台运行时装配
   │  ├─ kudu/                 连接管理、类型转换、谓词、分区、写入模板、测试行生成
   │  ├─ service/              表清单/结构/行读写/DDL/测试动作/调度/表比对/集群状态
   │  ├─ service/webui/        master Web UI 解析（/masters、/tablet-servers、/metrics?raw）
   │  ├─ job/                  异步任务（清空 / 删库 / 按前缀删表 / 测试调度）
   │  ├─ model/                DTO（record）
   │  └─ web/                  REST 控制器与统一异常
   └─ resources/
      ├─ application.yml
      └─ static/               Vite 构建产物（由 jar 直接提供）
```

## REST 接口

表级 DDL 与行读写接口用 `?table=<完整表名>`；批量操作、测试动作与表比对的目标表放在 body 里。

平台选择：请求头 `X-Kudu-Cluster: <平台id>`（或 `?cluster=<id>`）；不带则用服务端激活的平台。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/clusters` | 平台清单 + 激活平台 |
| POST | `/api/clusters` | 新增平台 |
| PUT | `/api/clusters/{id}` | 编辑平台（立即重连） |
| DELETE | `/api/clusters/{id}` | 删除平台（只删配置） |
| POST | `/api/clusters/{id}/activate` | 切换激活平台 |
| POST | `/api/clusters/test` | 用候选地址试连（不落库） |
| GET | `/api/cluster/status` | 当前平台的集群总览 |
| GET | `/api/meta/limits` | 全局查询限制 |
| GET | `/api/meta/version` | 应用版本、git commit、构建时间、镜像名 |
| GET | `/api/tables` | 按库分组的表清单 |
| GET | `/api/table?table=` | 表结构详情 |
| POST | `/api/table/query?table=` | 条件查询 + 分页 |
| POST | `/api/table/rows?table=` | INSERT / UPSERT / UPDATE / DELETE |
| POST | `/api/table/create` | 建表（表名在 body） |
| POST | `/api/table/drop?table=` | 删表（body 需 `confirm=<完整表名>`） |
| POST | `/api/table/rename?table=` | 重命名 |
| POST | `/api/table/columns/add`、`/columns/drop` | 加列 / 删列 |
| POST | `/api/table/replicas?table=` | 改副本数 |
| POST | `/api/jobs/truncate?table=` | 提交清空任务 |
| POST | `/api/jobs/drop-db?db=` | 提交删库任务（body 需 `confirm=<库名>`、`acknowledge=true`） |
| GET | `/api/tables/prefix?prefix=` | 按前缀删表预览（命中的完整表名、被跳过的系统表数量） |
| POST | `/api/jobs/drop-prefix?prefix=` | 提交按前缀删表任务（body 需 `confirm=<前缀>`、`acknowledge=true`） |
| POST | `/api/test/run` | 立即执行测试动作（body 含 `tables` 与 `actions`） |
| GET/POST | `/api/test/schedules` | 定时任务列表 / 新建 |
| PUT/DELETE | `/api/test/schedules/{id}` | 编辑 / 删除定时任务 |
| POST | `/api/test/schedules/{id}/run`、`/toggle` | 立即执行一次 / 启用暂停 |
| GET | `/api/test/schedules/{id}/runs` | 最近执行记录 |
| POST | `/api/compare` | 启动比对（`mode=table` 时 body 含 `leftTable` / `rightTable`；`mode=database` 含 `leftDb` / `rightDb`；`mode=prefix` 含 `leftDb` / `prefix`；均可选左右 `cluster`，`mode` 缺省为 `table`） |
| GET | `/api/compare/{id}` | 比对进度与结果 |
| POST | `/api/compare/{id}/cancel` | 取消比对 |
| GET | `/api/compare/tables?cluster=` | 指定平台的表清单（供左右两侧选择） |
| GET | `/api/compare/meta` | 比对行数与样本数限制 |
| GET | `/api/jobs`、`/api/jobs/{id}` | 任务列表/详情（带平台 id） |
| POST | `/api/jobs/{id}/cancel` | 取消任务 |

错误响应统一为：

```json
{ "code": "VALIDATION_FAILED", "message": "…", "details": { "fields": [ … ] } }
```

`code` 取值：`KUDU_UNAVAILABLE`、`KUDU_TIMEOUT`、`TABLE_NOT_FOUND`、`TABLE_EXISTS`、`VALIDATION_FAILED`、`UNSUPPORTED`、`CONFIRM_REQUIRED`、`JOB_CONFLICT`、`CLUSTER_NOT_FOUND`、`SCHEDULE_NOT_FOUND`、`COMPARE_NOT_FOUND`、`INTERNAL`。

## 实现要点与限制

- **多平台隔离**：每个平台一个 `ClusterRuntime`（独立 KuduClient、表缓存、Web UI 统计缓存），配置存 `config/clusters.json`；某个平台连不上只影响它自己。平台按需懒建连，不会在启动时把所有集群都连一遍。
- **版本信息来自构建参数**：镜像构建时把 `APP_VERSION` / `GIT_COMMIT` / `BUILD_TIME` / `APP_IMAGE` 写进环境变量与 OCI label；`/api/meta/version` 和顶栏读取同一份数据。本地 `java -jar` 没有这些变量时回落到 pom 版本（`source=pom`）。
- **删库是批量的，不是原子的**：按前缀逐个 `deleteTable`，中途失败会继续删剩下的表，最终在任务结果里列出成功的张数与失败原因；删库前会拒绝执行，如果该库下有表正跑着 truncate。Impala 托管表也能删，但删完要在 Impala 里 `INVALIDATE METADATA`。
- **按前缀删表匹配完整表名**：忽略大小写，同时比对剥离 `impala::` 后的名字（前缀 `db.` 能命中 `db.t1` 与 `impala::db.t2`）；前缀按字面量处理，`*` / `%` 不做通配；`sys.*` / `kudu.*` 系统表永不删除，预览里只报告跳过数量。与运行中的单表、删库或相交前缀任务冲突时拒绝提交。
- **测试调度是「造变更」不是备份**：删除随机主键行、按 schema 生成新行（整型主键取扫描最大值+1，字符串主键带运行种子，复合主键逐列递增），修改表结构只支持加可空列（Kudu 不能改列类型）。`REPLACE` 模式会先删上一轮自动加的 `bk_test_` 列再加新列，避免列无限增长；手动重复执行也记住上一轮的列。
- **测试任务按固定间隔轮询**：间隔 1 分钟~24 小时，可设最多执行次数与结束时间；同一任务上一轮未结束、或与 truncate / 删表 / 删库冲突时跳过本轮并记录。配置存 `config/test-schedules.json`，重启保留，服务停机错过的时间点只补跑一次。
- **表比对是只读的**：源表整表扫进内存（主键 → 整行编码），再扫目标表逐行对账，报目标缺失 / 多出 / 内容不一致与前 N 条样本。schema 按列名排序比对，列顺序、encoding、compression、副本数、分区不参与；DECIMAL/VARCHAR 属性和主键顺序参与。默认最多 20 万行，超过任务失败；比对期间写入会让结果不可信。
- **按库 / 按前缀比对是批量单表比对**：`mode=database` 选左右两个库，`mode=prefix` 只选源库并填统一恢复前缀（目标库 = 前缀 + 源库，如 `t_1028_` + `rec_` → `rec_t_1028_`）；两库的表按表短名（最后一节）配对，配对范围是两侧短名的并集，只在一侧存在的表报告「源缺表 / 目标缺表」。每张表复用单表比对口径，行数上限与样本数按单表计；结果只汇总每张表的结论与差异数，不含行级样本，需要样本时对单表再发起比对（前端结果表有「详情」按钮直接带出）。
- **分页是「扫描 + 跳过 offset」**：Kudu 没有 OFFSET，且 hash 分区下扫描不保证主键全局有序，因此不做主键游标翻页。`limit ≤ 500`、`offset ≤ 10000`、单次扫描 ≤ 50000 行，超限直接拒绝，避免拖垮 tserver。行数只给估算值，不跑 `COUNT(*)`。
- **只支持 AND**：Kudu 谓词无 OR。`NE` 与 `PREFIX` 的后缀校验在客户端完成（响应里 `clientFiltered=true`）；`NE` 按 SQL 语义排除 NULL。
- **写入无事务**：`MANUAL_FLUSH` 分批提交，失败行逐条返回，已提交的行不会回滚。
- **改副本数受限**：Kudu 1.17.1 的 Java 客户端没有 `setNumReplicas`，接口会返回 422 并给出可直接执行的 `kudu table set_replication_factor --num-replicas=N <table>` 命令，不做「复制表重建」。
- **清空不回收空间**：truncate 只是删行，磁盘空间要等 Kudu 后台 compaction；超过 `max-rows`（默认 200 万）会失败并建议改用 DROP。
- **表缓存**：`KuduTable` 的 schema 是打开瞬间的快照，DDL 后服务端会主动失效缓存；若有其它客户端改表结构，点「刷新」重新打开。
- **不支持 Kerberos 集群**：当前测试集群未开启，需要时得补 keytab 登录。
- **无鉴权**：仅测试环境使用。默认监听 `0.0.0.0:8088`，如需限制来源改 `server.address: 127.0.0.1`。

## 测试

```bash
mvn test          # 单元测试：类型转换、表名解析、前缀匹配、任务冲突、测试数据生成、表比对签名、批量比对配对、版本信息、DDL 校验
```

接口冒烟（会对集群建临时表再删掉，需要控制台已启动）：

```bash
python tools/api-smoke.py
KUDU_CLUSTER=c17eeff58 python tools/api-smoke.py     # 指定平台
KUDU_CONSOLE_URL=http://localhost:9090 python tools/api-smoke.py
```

## 维护约定

- 新增或修改功能时，同步更新本 README 的「功能」「REST 接口」「实现要点与限制」和「测试」；接口或行为变了但 README 没跟上，视为未完成。
- 破坏性操作、匹配规则、默认上限这类会影响使用预期的改动，必须写进「实现要点与限制」。
- 部署方式的变化同步维护 DEPLOY.md / ALIYUN-ACR.md，README 只保留 Docker 一键部署。
