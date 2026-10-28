# Kudu 控制台

[![CI](https://github.com/999bug/kudu-crud/actions/workflows/ci.yml/badge.svg)](https://github.com/999bug/kudu-crud/actions/workflows/ci.yml)

测试环境用的 Apache Kudu 管理台：一个 Java 进程同时提供 REST 接口与 Vue 前端页面，前后端在同一个工程目录内，一条命令启动。

支持**多个 Kudu 平台**：在界面里添加任意多套 master 地址，侧栏一键切换；每个平台独立建连、独立缓存，某个平台连不上不会影响其它平台。启动时只从 `application.yml` 初始化第一个平台，之后以 `config/clusters.json` 为准。

## 功能

| 模块 | 能力 |
| --- | --- |
| 平台管理 | 添加 / 编辑 / 删除 / 测试连接 / 切换当前平台；每个平台可单独设置 master 地址、超时、Web UI 开关与端口偏移 |
| 集群状态 | master 列表（角色 LEADER/FOLLOWER、RPC/HTTP 地址、启动时间、版本）、tablet server 存活与心跳、clusterId、表数量；Web UI 不可达时自动降级为 RPC 侧信息 |
| 表清单 | 按库分组（Kudu 无 catalog，`db` 取自表名前缀）、搜索、行数与磁盘占用（master 统计估算）、impala 托管表标记 |
| 表结构 | 列名/类型/主键/可空/precision/scale/length/编码/压缩/默认值/注释、副本数、hash 与 range 分区、分区边界 |
| 行级 CRUD | 条件查询 + 分页、单行/批量 INSERT / UPSERT / UPDATE / DELETE，逐行错误返回 |
| 表级 DDL | 可视化建表（列定义 + hash/range 分区 + 预览）、删表、重命名、加列、删列、改副本数 |
| 清空数据 | Kudu 无 truncate，用「扫描主键 + 批量 Delete」异步任务实现，带进度与取消 |
| 任务中心 | 查看清空任务进度、取消，任务带平台归属 |

查询条件支持 `EQ / NE / LT / LTE / GT / GTE / IN / IS_NULL / IS_NOT_NULL / PREFIX`，仅 AND 组合。

## 快速开始

### Docker（服务器部署推荐）

宿主只需 Docker + Docker Compose，不用装 JDK / Maven / Node —— 镜像里三阶段构建已经做完了。

```bash
cp .env.example .env          # 改 KUDU_MASTERS，需要时改 HOST_PORT
docker compose up -d --build  # 首次约 3~8 分钟，主要在拉 Maven 依赖
docker compose logs -f kudu-console
```

浏览器打开 `http://<机器IP>:8088/`，集群状态页能出 LEADER / FOLLOWER 就是通了。

```bash
curl -s http://127.0.0.1:8088/api/cluster/status | head -c 200   # 命令行验证
```

日常操作：

```bash
docker compose ps                     # 状态
docker compose restart kudu-console   # 重启
docker compose down                   # 停止并删除容器（挂载目录不受影响）
```

**升级**：`release/` 是 jar 的投放目录，把新版本放进去再重启即可；用脚本会更省事（自动校验 + 失败回滚，见 [DEPLOY.md](DEPLOY.md)）：

```bash
./deploy/upgrade.sh
```

`release/` 为空时容器会用镜像内置的 jar 启动，所以首次部署不需要手动准备 jar。

**公网访问**：不占公网 IP、不开端口，走 Cloudflare 隧道：

```bash
# 先在 .env 填 TUNNEL_TOKEN，并到 Zero Trust 配好 Public Hostname 与 Access 策略
docker compose --profile tunnel up -d
```

状态与日志落在 `./data/config`、`./data/logs`，容器重建不丢。

### Windows

```bat
:: 直接启动（默认 8088）
start.bat

:: 指定端口
start.bat 9090
```

`target\kudu-console.jar` 已构建时无需 Maven/Node；缺失时会自动调用 `build.bat` 重新构建。

重新构建：

```bat
build.bat
```

### Linux / macOS

```bash
chmod +x start.sh build.sh
./start.sh          # 默认 8088
./start.sh 9090
./build.sh          # 前端 npm build + mvn package
```

### 环境要求

- JDK 17
- 仅构建时需要：Maven 3.6+、Node.js 18+
- 仅 Docker 方式：Docker 20.10+ 与 Compose v2，无需上面三项

运行日志写在 `logs/kudu-console.log`；平台清单写在 `config/clusters.json`（两者都不入库）。

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
   │  ├─ kudu/                 连接管理、类型转换、谓词、分区、写入模板
   │  ├─ service/              表清单/结构/行读写/DDL/集群状态
   │  ├─ service/webui/        master Web UI 解析（/masters、/tablet-servers、/metrics?raw）
   │  ├─ job/                  异步任务（truncate）
   │  ├─ model/                DTO（record）
   │  └─ web/                  REST 控制器与统一异常
   └─ resources/
      ├─ application.yml
      └─ static/               Vite 构建产物（由 jar 直接提供）
```

## REST 接口

所有涉及具体表的接口都用查询参数 `?table=<完整表名>`（表名含 `.`、`impala::`，不放路径）。

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
| GET | `/api/jobs`、`/api/jobs/{id}` | 任务列表/详情（带平台 id） |
| POST | `/api/jobs/{id}/cancel` | 取消任务 |

错误响应统一为：

```json
{ "code": "VALIDATION_FAILED", "message": "…", "details": { "fields": [ … ] } }
```

`code` 取值：`KUDU_UNAVAILABLE`、`KUDU_TIMEOUT`、`TABLE_NOT_FOUND`、`TABLE_EXISTS`、`VALIDATION_FAILED`、`UNSUPPORTED`、`CONFIRM_REQUIRED`、`JOB_CONFLICT`、`INTERNAL`。

## 实现要点与限制

- **多平台隔离**：每个平台一个 `ClusterRuntime`（独立 KuduClient、表缓存、Web UI 统计缓存），配置存 `config/clusters.json`；某个平台连不上只影响它自己。平台按需懒建连，不会在启动时把所有集群都连一遍。
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
mvn test          # 15 个单元测试：类型转换、表名解析、DDL 校验
```

接口冒烟（会对集群建临时表再删掉，需要控制台已启动）：

```bash
python tools/api-smoke.py
KUDU_CLUSTER=c17eeff58 python tools/api-smoke.py     # 指定平台
KUDU_CONSOLE_URL=http://localhost:9090 python tools/api-smoke.py
```

## 发布与部署

这个项目不是静态站点，**GitHub Pages 部署不了**（跑不了 JVM，也连不到内网 Kudu）。GitHub 负责源码托管 + 自动出包，进程必须跑在能访问 Kudu master 的机器上。

```bash
git tag v1.0.1 && git push origin v1.0.1    # 触发 Actions，产出 jar + sha256 到 Release
```

服务器上在线升级（版本比对 → 校验 → 原子替换 → 起不来自动回滚）：

```bash
cd /opt/kudu-console && ./deploy/upgrade.sh
```

Docker 一键起服务：

```bash
cp .env.example .env && vi .env
docker compose up -d --build
```

完整的三种上线方式、公网暴露与鉴权（Cloudflare Tunnel / Caddy）、回滚与巡检配置见 **[DEPLOY.md](DEPLOY.md)**。
