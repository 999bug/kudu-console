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
| 表级 DDL | 可视化建表（列定义 + hash/range 分区 + 预览）、删表（表列表行内删除 / 表详情高级页）、重命名、加列、删列、改副本数 |
| 删库 | Kudu 没有真正的库，库只是表名前缀：一键删除该前缀下所有表，异步任务带进度与取消，删前需手输库名并列出全部表 |
| 按前缀删表 | 任意表名前缀匹配（忽略大小写，同时覆盖 `impala::` 托管表），先预览命中清单再异步逐张删除；系统表自动跳过，可取消 |
| 清空数据 | Kudu 无 truncate，用「扫描主键 + 批量 Delete」异步任务实现，带进度与取消 |
| 测试调度 | 多选表后一键删除数据 / 加列 / 添加数据，可立即执行或按固定间隔重复；用于验证全量、增量备份是否捕获变更 |
| 表比对 | 选两张表（可跨平台）比对列结构、主键与全量行内容；报目标缺失 / 多出 / 内容不一致及样本，异步显示进度 |
| 任务中心 | 查看清空、删库、按前缀删表、测试调度任务进度，取消任务，任务带平台归属 |

查询条件支持 `EQ / NE / LT / LTE / GT / GTE / IN / IS_NULL / IS_NOT_NULL / PREFIX`，仅 AND 组合。

## 快速开始

### Docker 部署（推荐）

服务器只需要 Docker 20.10+ 和 Compose v2，不用装 JDK / Maven / Node —— 镜像里已经打包好 JRE、前端页面和全部依赖。

镜像由流水线在每次 push 后自动构建，服务器只负责拉取。

#### 一、首次部署

服务器上不了 GitHub 也没关系：只从有网的机器传两个小文件过去，不用 clone 源码。

**1. 传文件**（在 Windows / 有网的机器上执行）

```bash
scp docker-compose.prod.yml root@<服务器IP>:/opt/kudu-console/
scp .env.example root@<服务器IP>:/opt/kudu-console/.env
```

**2. 配置**（在服务器上）

```bash
cd /opt/kudu-console
mkdir -p data/config data/logs
vi .env
```

`.env` 至少改这两项：

```env
KUDU_MASTERS=192.168.46.23:7051,192.168.46.23:7151,192.168.46.23:7251
IMAGE=ghcr.io/999bug/kudu-console:v1.0.1
```

**3. 启动**

```bash
docker compose -f docker-compose.prod.yml up -d
```

首次要拉完整镜像（约 137MB，含 JRE 与全部依赖），耗时取决于到 ghcr.io 的网络。慢的话挂着让它跑就行——`docker pull` 按层下载，中断后重试会复用已完成的层。

实在等不了就走**离线包**：先导入再启动，之后照样走增量更新，见第七节。

**4. 验证**

```bash
docker compose -f docker-compose.prod.yml ps
curl -s http://127.0.0.1:8088/api/cluster/status | head -c 300
```

浏览器打开 `http://<服务器IP>:8088/`，集群状态页出现 LEADER / FOLLOWER 就成功了。

#### 二、更新代码

**1. 推送**（本地）

```bash
git add <改动文件> && git commit -m "说明"
git push origin main
```

流水线自动完成：跑测试 → 构建前端 → 打包 jar → 构建镜像 → 推 ghcr.io → 自动打 tag（`v1.0.2`、`v1.0.3`…）→ 发布 Release。**不用手动打 tag。**

**2. 换版本**（服务器）

```bash
cd /opt/kudu-console
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

`up -d` 发现镜像变了会自动重建容器，`data/` 里的配置和日志不受影响。

**是增量下载**：镜像按 Spring Boot 分层构建，依赖（35MB）和业务代码（约 1MB）是不同的层。只改业务代码时，`pull` 只下 1MB 左右，不会重传依赖。只有改 `pom.xml` 或基础镜像才会动到大层。

想彻底不用管，挂个 cron 每 5 分钟检查一次：

```cron
*/5 * * * * cd /opt/kudu-console && docker compose -f docker-compose.prod.yml pull -q && docker compose -f docker-compose.prod.yml up -d >> logs/auto-update.log 2>&1
```

> cron 全自动必须用 `IMAGE=...:latest`，固定版本号不会被动更新。

#### 三、回滚

改 `.env` 里的版本号再 `up -d`：

```env
IMAGE=ghcr.io/999bug/kudu-console:v1.0.1
```

```bash
docker compose -f docker-compose.prod.yml up -d
```

历史版本都在 [Releases](https://github.com/999bug/kudu-crud/releases)，tag 永久保留。

#### 四、常用命令

```bash
docker compose -f docker-compose.prod.yml ps         # 状态
docker compose -f docker-compose.prod.yml logs -f    # 实时日志
docker compose -f docker-compose.prod.yml restart    # 重启
docker compose -f docker-compose.prod.yml down       # 停止并删除容器（data/ 不受影响）
```

嫌 `-f` 太长就先 `export COMPOSE_FILE=docker-compose.prod.yml`，之后直接 `docker compose ps`。

#### 五、公网访问

不占公网 IP、不开端口，走 Cloudflare 隧道：

1. `.env` 填 `TUNNEL_TOKEN=...`
2. Zero Trust 里配 Public Hostname，指向 `http://kudu-console:8088`
3. 启动带隧道的编排：

```bash
docker compose -f docker-compose.prod.yml --profile tunnel up -d
```

**务必同时配 Access 策略**（邮箱白名单）。这个控制台没有登录鉴权，能删表删库，裸挂公网等于把数据库交出去。

#### 六、国内镜像仓库（可选）

ghcr.io 在国外，首次拉 139MB 会慢。可以同步一份到国内的容器仓库，之后从国内拉。以阿里云 ACR 为例（个人版免费）：

1. 阿里云控制台 → 容器镜像服务 → 开通个人版 → 创建命名空间（如 `kuducrud`）
2. 该命名空间 → 访问凭证 → 设置**固定密码**（不是临时 token，不会过期）
3. GitHub 仓库 → Settings → Secrets and variables → Actions，添加四个：

| Secret 名称 | 值 |
| --- | --- |
| `ACR_REGISTRY` | `registry.cn-hangzhou.aliyuncs.com`（按你选的地域） |
| `ACR_NAMESPACE` | 命名空间，如 `kuducrud` |
| `ACR_USERNAME` | 阿里云账号全名或 RAM 用户名 |
| `ACR_PASSWORD` | 第 2 步设置的固定密码 |

配好后下次 push 会自动同步一份过去，Actions 日志里能看到 `已同步到 ...`。没配就自动跳过，不影响 ghcr 主链路。

服务器上换成国内地址：

```bash
docker login registry.cn-hangzhou.aliyuncs.com
```

```env
IMAGE=registry.cn-hangzhou.aliyuncs.com/kuducrud/kudu-console:v1.0.4
```

腾讯云 TCR（`ccr.ccs.tencentyun.com`）、华为云 SWR 用的是同一套 Docker Registry 协议，把 `ACR_REGISTRY` 换成对应域名即可。

> 阿里云个人版仓库默认私有，服务器需要 `docker login` 一次；固定密码不会过期。

#### 七、首次拉取太慢 / 完全断网

绕过 ghcr.io 的首次全量下载，但**不放弃后续的增量更新**——这是推荐给国内网络的做法。

**1. 在有网的机器上下载离线包**（这台 Windows 就行）

```bash
# 文件名带版本号，到 Releases 页面拿实际链接
curl -fL -O https://github.com/999bug/kudu-crud/releases/download/v1.0.5/kudu-console-image-v1.0.5.tar.gz
scp kudu-console-image-v1.0.5.tar.gz root@<服务器IP>:/tmp/
```

**2. 服务器上导入并启动**

```bash
gunzip -c /tmp/kudu-console-image-v1.0.5.tar.gz | docker load
docker images | grep kudu-console          # 应能看到 ghcr.io/... 和 kudu-console 两个名字
cd /opt/kudu-console
docker compose -f docker-compose.prod.yml up -d
```

包里同时打进了 `ghcr.io/999bug/kudu-console:v1.0.5` 和 `kudu-console:v1.0.5` 两个 tag，和 `.env` 里的 `IMAGE` 对得上，**不需要再 `docker tag`**。

**3. 之后照常增量更新**

```bash
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

本地已有的层和 registry 上的层 digest 一致，`pull` 会直接跳过它们，只下变化的那 0.17MB。所以「离线起步 + 在线增量」是可以长期共存的，不会因为导入过离线包就断了更新链路。

> 完全隔离的内网环境（连 ghcr.io 都不通）就每版都用离线包覆盖，做法一样，只是每次要换文件名。

更完整的离线方案见 [DEPLOY.md](DEPLOY.md) 的「目标机器上不了外网」。

#### 八、从源码构建（开发用）

上面都是拉现成镜像。要在本机构建镜像，用另一套编排：

```bash
cp .env.example .env
docker compose up -d --build     # 首次 3~8 分钟，主要在拉 Maven 依赖
```

升级把新 jar 放进 `release/` 再重启，或运行 `./deploy/upgrade.sh`（自动校验 + 失败回滚）。`release/` 为空时容器用镜像内置的 jar 启动。

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
| POST | `/api/jobs/drop-db?db=` | 提交删库任务（body 需 `confirm=<库名>`、`acknowledge=true`） |
| GET | `/api/tables/prefix?prefix=` | 按前缀删表预览（命中的完整表名、被跳过的系统表数量） |
| POST | `/api/jobs/drop-prefix?prefix=` | 提交按前缀删表任务（body 需 `confirm=<前缀>`、`acknowledge=true`） |
| POST | `/api/test/run` | 立即执行测试动作（body 含 `tables` 与 `actions`） |
| GET/POST | `/api/test/schedules` | 定时任务列表 / 新建 |
| PUT/DELETE | `/api/test/schedules/{id}` | 编辑 / 删除定时任务 |
| POST | `/api/test/schedules/{id}/run`、`/toggle` | 立即执行一次 / 启用暂停 |
| GET | `/api/test/schedules/{id}/runs` | 最近执行记录 |
| POST | `/api/compare` | 启动表比对（body 含 `leftTable` / `rightTable`，可选左右 `cluster`） |
| GET | `/api/compare/{id}` | 比对进度与结果 |
| POST | `/api/compare/{id}/cancel` | 取消比对 |
| GET | `/api/compare/tables?cluster=` | 指定平台的表清单（供左右两侧选择） |
| GET | `/api/jobs`、`/api/jobs/{id}` | 任务列表/详情（带平台 id） |
| POST | `/api/jobs/{id}/cancel` | 取消任务 |

错误响应统一为：

```json
{ "code": "VALIDATION_FAILED", "message": "…", "details": { "fields": [ … ] } }
```

`code` 取值：`KUDU_UNAVAILABLE`、`KUDU_TIMEOUT`、`TABLE_NOT_FOUND`、`TABLE_EXISTS`、`VALIDATION_FAILED`、`UNSUPPORTED`、`CONFIRM_REQUIRED`、`JOB_CONFLICT`、`INTERNAL`。

## 实现要点与限制

- **多平台隔离**：每个平台一个 `ClusterRuntime`（独立 KuduClient、表缓存、Web UI 统计缓存），配置存 `config/clusters.json`；某个平台连不上只影响它自己。平台按需懒建连，不会在启动时把所有集群都连一遍。
- **删库是批量的，不是原子的**：按前缀逐个 `deleteTable`，中途失败会继续删剩下的表，最终在任务结果里列出成功的张数与失败原因；删库前会拒绝执行，如果该库下有表正跑着 truncate。Impala 托管表也能删，但删完要在 Impala 里 `INVALIDATE METADATA`。
- **按前缀删表匹配完整表名**：忽略大小写，同时比对剥离 `impala::` 后的名字（前缀 `db.` 能命中 `db.t1` 与 `impala::db.t2`）；前缀按字面量处理，`*` / `%` 不做通配；`sys.*` / `kudu.*` 系统表永不删除，预览里只报告跳过数量。与运行中的单表、删库或相交前缀任务冲突时拒绝提交。
- **测试调度是「造变更」不是备份**：删除随机主键行、按 schema 生成新行（整型主键取扫描最大值+1，字符串主键带运行种子，复合主键逐列递增），修改表结构只支持加可空列（Kudu 不能改列类型）。`REPLACE` 模式会先删上一轮自动加的 `bk_test_` 列再加新列，避免列无限增长；手动重复执行也记住上一轮的列。
- **测试任务按固定间隔轮询**：间隔 1 分钟~24 小时，可设最多执行次数与结束时间；同一任务上一轮未结束、或与 truncate / 删表 / 删库冲突时跳过本轮并记录。配置存 `config/test-schedules.json`，重启保留，服务停机错过的时间点只补跑一次。
- **表比对是只读的**：源表整表扫进内存（主键 → 整行编码），再扫目标表逐行对账，报目标缺失 / 多出 / 内容不一致与前 N 条样本。schema 按列名排序比对，列顺序、encoding、compression、副本数、分区不参与；DECIMAL/VARCHAR 属性和主键顺序参与。默认最多 20 万行，超过任务失败；比对期间写入会让结果不可信。
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
mvn test          # 单元测试：类型转换、表名解析、前缀匹配、任务冲突、测试数据生成、表比对签名、DDL 校验
```

接口冒烟（会对集群建临时表再删掉，需要控制台已启动）：

```bash
python tools/api-smoke.py
KUDU_CLUSTER=c17eeff58 python tools/api-smoke.py     # 指定平台
KUDU_CONSOLE_URL=http://localhost:9090 python tools/api-smoke.py
```

## 发布产物

`git push origin main` 即发版，流水线产出三样东西：

| 产物 | 用途 |
| --- | --- |
| `kudu-console.jar` + `.sha256` | 直接 `java -jar`，或给 `deploy/upgrade.sh` 在线升级 |
| `ghcr.io/999bug/kudu-console:<tag>` 与 `:latest` | Docker 部署与更新 |
| `kudu-console-image-<tag>.tar.gz` | 完全离线时 `docker load` |

版本号自动递增 `v1.0.<run_number>`。要指定版本就 `git tag v1.0.5 && git push origin v1.0.5`，或 Actions 手动触发。Release 只保留最近 20 个，tag 全部保留。

为什么不能部署到 GitHub Pages：这是 JVM 进程 + Kudu 客户端，静态托管跑不了 Java，也连不到内网集群。

内网直连、Cloudflare Tunnel、公网反代 + Basic Auth、离线部署与故障排查见 **[DEPLOY.md](DEPLOY.md)**。
