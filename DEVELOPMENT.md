# 开发与发布规范

**定位**：本文是维护者视角的规则手册——项目怎么初始化、代码怎么改、怎么自测、怎么发版、怎么部署回滚。README 面向使用者，DEPLOY.md 面向部署，ALIYUN-ACR.md 面向国内镜像，本文只讲「怎么做才不算出错」。

**适用范围**：本仓库（`kudu-console`），以及后续同类项目——**Java 服务 + 前端静态产物打进同一个 jar、Docker 交付、GitHub Actions 发布**。

**维护规则**：流水线、目录结构、发布动作一变，先改本文再改代码。文档与代码不一致，按 bug 处理，见 [附录 C](#附录-c-文档维护矩阵)。

最后核对：`v1.0.11`（2026-10-30）。

---

## 1. 技术决策基线

这些是已经定型、有理由的选择。改动它们需要单独说明收益与代价，不能作为顺手重构的一部分。

| 决策 | 现状 | 理由 / 代价 |
| --- | --- | --- |
| 交付形态 | 单 Java 进程同时供 REST 与页面 | 部署只需一个镜像；代价是前端必须构建进 `static/`，不能独立部署 |
| 后端 | Java 17 + Spring Boot 3.3.12 | 与 Kudu 客户端 1.17.1 对齐；JDK 17 是 CI 与本机统一版本 |
| 前端 | Vue 3 + Vite 5，无 UI 库、无 CDN | 内网可用，无外链依赖；样式自己写 |
| 前端产物 | `vite build` 直接输出到 `src/main/resources/static/` | 少了搬运步骤；代价是该目录**不入库**，构建顺序必须是 npm 先、maven 后 |
| 持久化 | `config/*.json`（平台清单、定时任务） | 测试工具不值得引入数据库；删文件即回到 yml 初始化 |
| 多平台 | 每平台一份 `ClusterRuntime`（独立连接 / 缓存） | 一个集群连不上不影响其它；代价是 service 不能有 Spring 单例状态 |
| 镜像 | ghcr.io 主 + 阿里云 ACR 备 | 国内直连 ghcr 不稳定；ACR 同步失败不阻塞发版 |
| 镜像分层 | `layertools` 拆四层 | 改业务代码只下 ~0.2MB，而不是 33MB |
| 发版触发 | 手动触发或打 tag，**push main 不发版** | 避免每次提交都产出 Release；CI 仍守住 main 质量 |
| 版本号 | `v1.0.<run_number>` 自动递增 | 不用维护文件；大改时手填 `v1.1.0` |
| 鉴权 | 无 | 仅测试环境；任何公网暴露必须外层加 Access / Basic Auth |

---

## 2. 项目初始化

### 2.1 初始化顺序

顺序不能乱，后面每一步都依赖前一步的产物。

1. **定仓**：建 GitHub 仓库，先决定公开还是私有——它决定 ghcr 包默认可见性、Actions 是否消耗额度、文档能不能写内网地址。
2. **配身份**：第一次 `git commit` 之前就把作者名与邮箱设成匿名（见 [9.1](#91-提交身份一律匿名)）。历史提交改起来代价极高。
3. **放忽略规则**：`.gitignore`、`.gitattributes`、`.dockerignore` 三件套先于任何代码提交。
4. **搭骨架**：后端 `src/main/java/<pkg>/{config,kudu,service,job,model,web}`，前端 `web/src/{views,components}`，`tools/` 放脚本。
5. **写脚本**：`build.sh` / `build.bat` / `start.sh` / `start.bat`，Windows 与 Linux 各一套，行为一致。
6. **写 README 骨架**：功能表、REST 接口、实现要点与限制、测试四章先立起来，后面每次改动往里填。
7. **写 `.env.example`**：所有环境变量在此列全，真实 `.env` 永远不入库。
8. **接 CI**：`ci.yml` 先跑通「前端构建 → 校验产物 → 单测 → 打包」。
9. **接发布**：`release.yml` 产出 jar + 镜像 + Release，首次跑通再谈优化。
10. **写部署文档**：DEPLOY.md（多形态部署）、ALIYUN-ACR.md（国内镜像）。README 只留最短路径。

### 2.2 必须有的文件

| 文件 | 作用 | 缺了会怎样 |
| --- | --- | --- |
| `.gitignore` | 挡住构建产物、运行时配置、本地环境 | `config/*.json` 泄露内网地址，`target/` 污染仓库 |
| `.gitattributes` | `.sh` 强制 LF、`.bat` 强制 CRLF | Windows 检出后在 Linux 报 `bad interpreter` |
| `.dockerignore` | 瘦身构建上下文 | 本地 `target/`、`node_modules/` 进上下文，构建变慢甚至串味 |
| `.env.example` | 环境变量唯一清单 | 部署时靠翻代码找变量名 |
| `Dockerfile` | 多阶段构建 | 无法交付 |
| `docker-compose.yml` / `docker-compose.prod.yml` | 源码构建 / 镜像部署 | 两套场景混用，服务器被迫装 JDK |
| `.github/workflows/ci.yml` | 提交即验证 | main 分支裸奔 |
| `.github/workflows/release.yml` | 发版与产物 | 手工打包，版本不可追溯 |
| `README.md` / `DEPLOY.md` / `DEVELOPMENT.md` | 使用 / 部署 / 规范 | 知识只在人脑里 |

### 2.3 命名约定

| 对象 | 规则 | 本仓库 |
| --- | --- | --- |
| 仓库 | 小写中划线 | `kudu-console` |
| 镜像 | 用产品名，不带仓库名后缀 | `kudu-console`（与仓库名一致） |
| Java 包 | 全小写，跟产品名 | `com.kuducrud` |
| 端口 | 应用固定端口，部署用 `HOST_PORT` 覆盖 | 应用 8088，本机自测 9099 |
| 版本 tag | `v<major>.<minor>.<patch>` | `v1.0.11` |
| 分支 | `main` 为唯一长期分支 | 短命分支用 `feat/xxx`、`fix/xxx` |

### 2.4 配置与运行时的边界

- 编译期常量 → `application.yml`
- 运行期可变、用户会改的 → `config/*.json`（已 gitignore），重启保留
- 环境相关、含凭据或内网地址的 → 环境变量 / `.env`
- 覆盖优先级从低到高：`application.yml` → 界面配置（`config/*.json`）→ 命令行参数 / 环境变量

这条边界一旦破坏（比如把平台地址写死进 yml 又指望界面能改），后面必然出「改了不生效」的故障。

---

## 3. 日常开发

### 3.1 分支策略

- `main` 始终可构建、可发布；不建 `develop`。
- 小改动（文档、单文件修复）直接提交 `main`。
- 跨多模块或需要评审的改动走 `feat/xxx`，合回 `main` 用 squash，保持 `main` 提交粒度干净。
- 禁止直接向 `main` force push（唯一例外：明确授权的历史改写，见 [10](#10-git-安全红线)）。

### 3.2 提交信息规范

本仓库此前没有强制格式（历史提交是无前缀中文短句）。**从本文生效起按下述格式提交**，历史提交不回改。

```
<类型>: <中文摘要>

- 变更点（为什么改、影响了什么）
- 变更点
```

**类型前缀**（小写，半角冒号 + 空格）：

| 前缀 | 用于 | 例 |
| --- | --- | --- |
| `feat` | 新增功能或接口 | `feat: 增加按前缀批量删表` |
| `fix` | 修复缺陷 | `fix: 修复窄屏顶栏地址溢出` |
| `docs` | 只改文档 | `docs: 补充 Docker 日志排查章节` |
| `refactor` | 不改外部行为的重构 | `refactor: 抽出 TableBatchDropper 复用` |
| `test` | 只改测试 | `test: 补批量配对静态方法单测` |
| `build` | 构建脚本、依赖、镜像 | `build: 镜像改 Spring Boot 分层` |
| `ci` | 工作流 | `ci: 发版改为手动触发` |
| `chore` | 忽略规则、格式、杂项 | `chore: 忽略本地截图目录` |

**摘要**：中文动宾短语，≤ 40 字，结尾不加句号，不用「更新」「修改一下」这类无信息量描述。

**正文**：`-` 列表，一条一个变更点，讲**为什么**和**影响了什么**，不复述 diff。单纯改文案、改注释可以只有一行摘要。

**粒度**：一次提交只做一件事。功能 + 测试 + README 更新属于同一件事，放同一个提交；两个不相关的修复不要合并。

**禁止**：

- 无信息量摘要（`update`、`fix bug`、`修改`）
- 把多种改动塞进一个提交（「修复 X 并优化 Y、调整 Z 样式」）
- 任何形式的 AI 署名或 `Generated with ...` 尾注
- 用 `-c user.email=...` 之类的方式绕过匿名身份
- 提交 `.env`、`config/*.json`、`logs/`、`data/`、`target/`、`web/node_modules/`

`release.yml` 用 `gh release create --generate-notes` 自动生成变更说明，提交信息写不清楚，Release 页面就没法看——这是格式要求而非洁癖。

### 3.3 提交前自测闭环

四步，缺一步就不算完成。任何一步失败都停在原地，不带病提交。

```bash
# 0. 环境（本机 PATH 里没有 java，必须显式指定）
export JAVA_HOME="C:/Program Files/Java/jdk-17.0.16+8"

# 1. 后端单测
"$HOME/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn" -B test

# 2. 前端构建 + 产物校验（必须在 mvn package 之前，否则 jar 里没有页面）
cd web && npm run build && cd ..
test -f src/main/resources/static/index.html

# 3. 打包并起真实服务，自测端口用 9099，不要占 8088
./build.sh          # Windows 用 build.bat；内部完成前端构建 + mvn clean package
"$JAVA_HOME/bin/java" -jar target/kudu-console.jar --server.port=9099

# 4. 接口冒烟（会对集群建临时表再删掉）
KUDU_CONSOLE_URL=http://localhost:9099 python tools/api-smoke.py
```

**前端改动额外要求**：涉及布局 / 响应式 / 交互时，用 Playwright + 本机 Chromium 实际点击并截图，至少覆盖 1440 / 820 / 700 三个宽度，确认无溢出、无错位、无被裁剪的按钮。仅靠 `npm run build` 通过不算验证。

**破坏性改动额外要求**（删表、删库、清空、批量操作）：必须在真实集群上跑一遍，并验证拒绝路径——缺少 `confirm`、`confirm` 值错误、`acknowledge=false`、目标不存在、系统表，全部要按预期拒绝。

**收尾**：停掉本会话起的进程，确认端口释放。Windows 下 jar 被占用会导致下一次 `mvn package` 失败。

### 3.4 代码约定

**分层**：`web`（控制器 + 统一异常）→ `service`（业务）→ `kudu`（客户端交互、类型转换、谓词、写入模板）→ `model`（DTO，用 record）。控制器不写业务，service 不碰 `HttpServletRequest`。

**无状态**：多平台靠 `ClusterRegistry.current()` 按请求解析平台，service 实例随平台创建，不加 Spring 单例状态。新增 service 时先想清楚「它属于平台还是属于进程」。

**错误响应**统一为：

```json
{ "code": "VALIDATION_FAILED", "message": "…", "details": { "fields": [ … ] } }
```

- `code` 是稳定契约，新增取值必须同步 README 的错误码列表。
- HTTP 状态与语义对应：参数错 400、不存在 404、冲突 409、不支持 422、集群不可用 503、未知 500。
- 异常信息给人看，别把堆栈丢给前端。

**上限必须显式**：分页、扫描行数、批量大小这类限制写在 `application.yml`，通过 `/api/meta/limits` 暴露，超限直接 400，**不允许静默截断**。

**破坏性接口必须二次确认**：请求体要求 `confirm=<完整名称>`（+ `acknowledge=true`），后端校验不通过就拒绝；前端配确认框，允许一键填入但保留手动输入。

**异步任务**统一进 `JobRegistry`：带平台归属、可按资源加锁（`t:<table>` / `d:<db>` / `p:<prefix>`）、可取消、冲突时拒绝提交而不是排队等待。

**日志**：`logs/kudu-console.log` 落盘 + 标准输出；关键路径（建连失败、任务开始/结束、DDL）带平台 id 与表名，便于 grep。

### 3.5 前端约定

- 无 UI 库、无 CDN、无外链字体；样式写在 `styles.css`，不引渐变色与装饰性元素。
- 构建产物 `src/main/resources/static/` **不入库**，由 `npm run build` 生成；`web/dist/` 同样忽略。
- `vite.config.js` 的 `base` 保持 `/`：产物由 jar 在根路径提供。若某天要挂子路径部署，`base` 与 SPA 回退要一起改。
- 新增页面必须在 SPA 回退白名单里注册，否则直接刷新会 404（历史踩过 `/test-ops`）。
- 平台选择统一由请求头 `X-Kudu-Cluster` 携带，不要偷偷塞进 body。
- 长文本容器一律 `ellipsis + title`，不要在窄屏靠换行硬撑。

### 3.6 文档同步

改完代码立即更新文档，与提交放在一起：

| 改动内容 | README | DEPLOY | ALIYUN-ACR | 本文 |
| --- | --- | --- | --- | --- |
| 新增/修改功能、接口、默认值、限制 | 必须 | — | — | 视情况 |
| 部署方式、环境变量、目录挂载 | 摘要 | 必须 | 涉及镜像源时 | — |
| 镜像仓库、Secrets、国内同步 | — | — | 必须 | — |
| 流水线、版本策略、提交规则 | — | — | — | 必须 |

README 至少要动到对应章节：「功能」「REST 接口」「实现要点与限制」「测试」。破坏性操作、匹配规则、默认上限这类会影响使用预期的改动，必须写进「实现要点与限制」。

---

## 4. 构建

### 4.1 本地构建

```bash
./build.sh          # Linux / macOS：前端 npm build → 校验 index.html → mvn clean package
build.bat           # Windows：同上
./start.sh 9090     # 起服务，默认 8088；jar 不存在时自动先构建
```

脚本里的硬约束，新增脚本时保持：

- 找不到 JDK / Maven 直接报错退出，不静默降级。
- `set -euo pipefail`（Windows 用 `if errorlevel` 逐段判断）。
- 前端构建后必须 `test -f src/main/resources/static/index.html`。
- 打包前 `clean`，避免残留 class 混进 jar。
- `.bat` 文件**只用 ASCII 文案**：Windows 批处理含中文会因代码页解析失败。

### 4.2 镜像构建规则

`Dockerfile` 是四阶段，顺序不能改：

1. `node:20-alpine`：`npm ci` → `npm run build`
2. `maven:3.9-eclipse-temurin-17`：先 `dependency:go-offline` 拉依赖（缓存层），再拷源码与前端产物，`package`
3. `eclipse-temurin:17-jre` + `layertools extract`：按 Spring Boot 分层拆包
4. 运行镜像：按 `dependencies → spring-boot-loader → snapshot-dependencies → application` 顺序 COPY（变更频率从低到高）

必须保持的性质：

- **基础镜像固定**（`17-jre` 不写 `17-jre-alpine` 混用），`curl` 供 HEALTHCHECK 与排障。
- **构建信息靠 build-args 注入**：`APP_VERSION` / `GIT_COMMIT` / `BUILD_TIME` / `APP_IMAGE`，同时写 ENV 与 OCI label。本地不传时落到 `dev` / `unknown`，不能因此构建失败。
- **`JAVA_OPTS` 默认值写小**（`-Xms256m -Xmx1g`），生产用 `.env` 放大，避免大表扫描打满机器。
- **HEALTHCHECK 打首页而不是 `/api/cluster/status`**：后者依赖集群连通性，集群抖动不该被判定为容器死亡。
- **双入口**：`/app/release/kudu-console.jar` 存在就跑它（源码/热升级模式），否则跑分层 `JarLauncher`（镜像模式）。改 `ENTRYPOINT` 前先确认两种部署方式都不坏。

本机没有 Docker，**镜像只能在 CI 验证**；涉及 Dockerfile 的改动，发版后必须确认镜像能起、`/api/meta/version` 返回的 commit 与本次提交一致。

### 4.3 忽略规则

三层各管一段，职责不重叠：

| 文件 | 管什么 | 关键条目 |
| --- | --- | --- |
| `.gitignore` | 不进仓库 | `target/`、`logs/`、`src/main/resources/static/`、`web/node_modules/`、`config/*.json`、`.env`、`release/`、`data/`、`.workbuddy/` |
| `.dockerignore` | 不进构建上下文 | `.git`、`target`、`logs`、`config/*.json`、`src/main/resources/static`、`web/node_modules`、`*.jar` |
| `.gitattributes` | 换行符与二进制 | `*.sh text eol=lf`、`*.bat text eol=crlf`、`*.jar binary`、`*.png binary` |

新增运行期产物的第一件事：判断它属于哪一层，然后加进对应文件。漏加 `.dockerignore` 的后果是构建变慢；漏加 `.gitignore` 的后果是内网地址进公开仓库。

---

## 5. 发布（GitHub Actions + Docker）

### 5.1 流水线分工

| 流水线 | 触发 | 做什么 | 不做什么 |
| --- | --- | --- | --- |
| `ci.yml` | push `main`、PR、手动 | 前端构建 → 校验产物 → 单测 → 打包 → 上传 artifact（7 天） | 不发版、不推镜像、不建 Release |
| `release.yml` | push tag `v*`、手动 `workflow_dispatch` | 定版本 → 构建 → 单测 → 打包 → sha256 → 构建镜像 → 推 ghcr → 同步 ACR（可选）→ 补 tag → 建 Release → 清理旧 Release | 不在 push main 时触发 |

两条流水线**都**先跑「前端构建 + 校验 `static/index.html` + `mvn test`」，`release.yml` 额外 `mvn -DskipTests package`（测试已在上一步跑过）。顺序不能反：`npm build` 必须在 `mvn package` 之前。

`concurrency`：CI 用 `cancel-in-progress: true`（新提交取消旧跑），Release 用 `false`（发版不能互相打断）。

### 5.2 版本号

- 默认 `v1.0.<GITHUB_RUN_NUMBER>`，自动递增，不需要维护版本文件。
- 手动触发可填版本号，必须符合 `vX.Y.Z`，**不能填已存在的 tag**。
- 语义：普通功能与修复递增 patch；不兼容改动或大版本重构手填 `v1.1.0`。
- tag 由流水线在 `workflow_dispatch` 场景下补推（bot 身份），用 `git push origin vX` 手动发布时则沿用已有 tag。

**已知副作用**：手填一个已存在的版本号时，`gh release upload --clobber` 会**覆盖该版本已发布的附件**，`--generate-notes` 也会基于当前状态重算。重发一律用新 patch 号。

### 5.3 发布步骤

```bash
# 1. 提交并推送（只跑 CI，不发版）
git add <具体文件>            # 不用 git add -A
git commit -m "feat: ..."
git push origin main

# 2. 发版（二选一）
#    方式一：Actions → Release → Run workflow → 分支 main → 版本号留空
git tag v1.0.12 && git push origin v1.0.12
```

发布前确认：CI 绿、README 已同步、破坏性改动已在真实集群验证过、工作区干净、没有把 `.env` / `config/*.json` 混进提交。

### 5.4 产物

| 产物 | 用途 |
| --- | --- |
| `ghcr.io/<owner>/kudu-console:<tag>` 与 `:latest` | Docker 部署与升级（主） |
| `kudu-console.jar` + `.sha256` | `java -jar`、systemd、`upgrade.sh` 在线升级 |
| `kudu-console-image-<tag>.tar.gz` | 完全离线环境 `docker load`；内含短名与完整名两个 tag，避免 compose 再拉全量 |
| 阿里云 ACR `<tag>` 与 `:latest` | 国内服务器拉取（配置了 Secrets 才有） |

Release 只保留最近 20 个（`gh release list` + `tail -n +21` 逐个删），tag 全部保留。清理步骤是 `continue-on-error`，清理失败不影响发版。

### 5.5 发布后验证

```bash
# 1. Release 三个附件都在
gh release view v1.0.12

# 2. 镜像能拉、能起
docker pull ghcr.io/999bug/kudu-console:v1.0.12

# 3. 部署后确认版本真的换了（唯一可信入口）
curl -s http://127.0.0.1:8088/api/meta/version
```

`/api/meta/version` 返回 `version` / `commit` / `buildTime` / `image`，`commit` 必须等于本次发版的 HEAD 短 SHA。页面顶栏显示同一个来源——「顶栏版本号没变」几乎总是镜像没换，而不是代码没生效。

### 5.6 回滚

| 形态 | 回滚动作 |
| --- | --- |
| 镜像 + compose | `.env` 把 `IMAGE` 改回上一个 tag → `docker compose -f docker-compose.prod.yml up -d` |
| jar + systemd / 热升级 | `./deploy/upgrade.sh v1.0.11`（走同一套下载 + 校验 + 健康检查） |
| 源码构建 | `release/` 放回旧 jar → 重启容器 |

回滚不需要重新构建，历史 tag 在仓库与 ACR 都保留。`data/`、`config/` 是挂载卷，回滚不动配置与日志。

### 5.7 权限与 Secrets

```yaml
permissions:
  contents: write   # 打 tag、建 Release
  packages: write   # 推 ghcr
```

| Secret | 必需 | 说明 |
| --- | --- | --- |
| `GITHUB_TOKEN` | 自动 | 推 ghcr、建 Release、改包可见性 |
| `ACR_REGISTRY` | 可选 | 裸域名，不带 `https://`、不带命名空间、结尾无 `/` |
| `ACR_NAMESPACE` | 可选 | ACR 命名空间 |
| `ACR_USERNAME` | 可选 | 访问凭证页的用户名（不是邮箱/手机号） |
| `ACR_PASSWORD` | 可选 | **固定密码**，不是登录密码、不是临时 token |

- 加在 **Repository secrets**，不是 Environment secrets（工作流没有引用 environment）。
- 未配置 `ACR_*` 时同步步骤自动跳过并 `exit 0`，不报错。
- 同步失败 `continue-on-error: true`：tag 与 Release 照常产出，修好 Secrets 后 Re-run failed jobs 即可。

### 5.8 发布纪律

- **不手改已发布的产物**：有问题发新版本，不覆盖旧 Release（离线包被覆盖会导致「同一版本两台机器行为不同」）。
- **不改历史 tag**：`git push --force origin --tags` 属禁止操作。
- **不在发版当天改流水线又改业务**：两者混在一起，出问题无法判断是代码还是流水线。
- **发版后 30 分钟内不离开**：至少确认一次服务器拉到新镜像且版本号正确。

---

## 6. GitHub Pages 发布流程（静态项目通用）

### 6.1 先判断适不适用

GitHub Pages 只能托管**静态产物**，跑不了 JVM，也没有服务端，更连不到内网。

| 项目类型 | 结论 |
| --- | --- |
| 纯前端 SPA、文档站、演示原型 | 适用 |
| 需要后端 API、数据库、内网资源 | **不适用**（本仓库属此类：Spring Boot + Kudu 客户端，必须跑在能连通 Kudu master 的机器上） |
| 需要密钥 / 服务端渲染 | 不适用 |

判断标准只有一句：**打出来的产物能否用静态文件服务器直接提供**。本仓库的兜底方案是 Docker（见 [5](#5-发布github-actions--docker)）与 `java -jar`。

### 6.2 一次性配置

1. 仓库 Settings → Pages → Build and deployment → **Source 选 GitHub Actions**（不要选 branch，避免产物入库）。
2. Settings → Actions → General → Workflow permissions 至少 `Read and write`（Pages 部署需要 `pages: write` + `id-token: write`，在 workflow 里声明即可）。
3. 自定义域名才需要 `CNAME`；用 `*.github.io/<repo>` 则跳过。
4. 仓库公开才有免费 Pages（私有仓库需付费计划）。

### 6.3 流水线模板

```yaml
name: Deploy Pages

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  pages: write        # 部署 Pages
  id-token: write     # OIDC，deploy-pages 需要

concurrency:
  group: pages
  cancel-in-progress: false   # 部署不能互相打断

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
      - run: npm ci
      - run: npm run build
      - name: SPA fallback
        run: cp dist/index.html dist/404.html   # history 路由必须，hash 路由可省
      - uses: actions/configure-pages@v5
      - uses: actions/upload-pages-artifact@v3
        with:
          path: dist

  deploy:
    needs: build
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - id: deployment
        uses: actions/deploy-pages@v4
```

### 6.4 构建要点

- **`base` 必须对**：项目页地址是 `https://<user>.github.io/<repo>/`，Vite 要设 `base: '/<repo>/'`；用户页（`<user>.github.io` 仓库）才是 `/`。`base` 写错的表现是页面白屏 + 资源 404。
- **产物不入库**：`dist/` 加入 `.gitignore`，由 CI 构建。提交产物会导致「仓库里的 dist 和源码不一致」这种经典事故。
- **SPA 回退**：history 路由必须把 `index.html` 复制成 `404.html`；hash 路由不需要。
- **`.nojekyll`**：Actions 走 artifact 上传，不经过 Jekyll，但建议保留该文件，切换回 branch 部署时不会因为下划线目录被吞。
- **不要放密钥**：产物里的任何 `VITE_*` 变量都会被打包，等同于公开。
- **不要在 Pages 上放大文件**：单仓库 Pages 有 1GB 站点上限、100GB/月流量软限制。

### 6.5 验证与回滚

- 部署成功后 Actions 的 `deploy` job 会给出 `page_url`，点开确认首页 200、刷新子路由不 404。
- 首次生效有缓存延迟，最长约 10 分钟；确认不是 `base` 配错再排查。
- 回滚 = 回退 `main` 上的构建提交后重新触发（Pages 不保留历史部署版本），或用 `workflow_dispatch` 指定旧 ref 重跑。

### 6.6 国内可用性

`*.github.io` 在国内访问不稳定，属已知事实，**不要在对外承诺里写「国内可直接访问」**。需要稳定访问时：自建静态托管 / 对象存储 + CDN，或反向代理到自有域名。这条同样适用于本仓库的 Release 下载（大文件建议走 ACR 或离线包）。

---

## 7. 国内镜像（阿里云 ACR）

### 7.1 为什么要有

ghcr.io 在国内经常拉不动或极慢。发版时同时推一份到国内 registry，服务器从国内拉，是唯一可靠的做法。

链路：

```
GitHub Actions 构建 → 推 ghcr.io（主）
                   → 同镜像 tag 一份到 ACR（备，失败不阻塞）
服务器 .env 的 IMAGE 指向 ACR → docker compose pull / up -d
```

### 7.2 仓库侧准备

1. 阿里云「容器镜像服务」开通**个人版**（免费）。
2. 建命名空间（如 `kuducrud`）+ 仓库 `kudu-console`，类型选**私有**，代码源选「本地仓库」。
3. 「访问凭证」页设**固定密码**（不会过期），记下**实例域名**、**用户名**、固定密码。
4. 镜像地址格式：`<crpi-实例域名>/<命名空间>/kudu-console:<tag>`。

### 7.3 流水线行为

`release.yml` 的 `Mirror to domestic registry` 步骤：

- 四个 `ACR_*` 任一为空 → 打日志跳过，`exit 0`。
- 使用前对四个变量 `tr -d '[:space:]'`，去掉粘贴时混入的换行 / 空格（历史上 `invalid control character in URL` 就是这个）。
- `docker login` 失败 → `::warning::` + `exit 0`，不阻塞后面补 tag 与建 Release。
- 成功后推 `<tag>` 与 `latest` 两个 tag。
- `continue-on-error: true`：整步失败也只标红，不影响发版。

### 7.4 服务器侧使用

```env
IMAGE=<crpi-实例域名>/<命名空间>/kudu-console:latest   # 或固定 :v1.0.12
```

```bash
sudo docker login <crpi-实例域名> --username=<用户名>
cd /opt/kudu-console
sudo docker compose -f docker-compose.prod.yml pull
sudo docker compose -f docker-compose.prod.yml up -d
curl -s http://127.0.0.1:8088/api/meta/version
```

- **cron 自动更新只能用 `latest`**（固定 tag 不会被动更新），且 **cron 用户必须与 `docker login` 用户一致**，否则拉取 401。
- 生产想稳：`cron` 只做 `pull`，`up -d` 手动执行；或固定 tag，升级即改号。

### 7.5 手动补同步

CI 同步失败且暂时不想改 Secrets 时，在能同时访问 ghcr 与 ACR 的机器上：

```bash
V=v1.0.12
SRC=ghcr.io/999bug/kudu-console:$V
DST=<crpi-实例域名>/<命名空间>/kudu-console
docker pull $SRC
docker tag $SRC $DST:$V && docker tag $SRC $DST:latest
docker push $DST:$V && docker push $DST:latest
```

### 7.6 报错对照

| 现象 | 原因 |
| --- | --- |
| `login attempt ... 403 Forbidden` | 密码不是固定密码 / 用户名不是访问凭证页的用户名 / RAM 未授权 / 域名与实际实例不一致 |
| `lookup ... no such host` | `ACR_REGISTRY` 带了 `https://` 或路径 |
| `invalid control character in URL` | Secret 值末尾混入换行（工作流已容错，仍建议重填） |
| `denied: requested access to the resource is denied` | 仓库不存在且未开自动建仓 / 未登录 / 账号无权限 |
| 服务器 pull 慢或失败 | `.env` 的 `IMAGE` 仍在 ghcr |
| cron 跑了没更新 | `IMAGE` 是固定 tag，或 cron 用户 ≠ 登录用户 |

---

## 8. 部署与升级

### 8.1 三种形态

| 形态 | 文件 | 适用 | 前提 |
| --- | --- | --- | --- |
| 镜像 + 生产编排 | `docker-compose.prod.yml` | **推荐**，服务器不需要源码 / JDK | 能连 registry |
| 源码构建 | `docker-compose.yml`（`build: .`，挂 `release/`） | 内网自建、需要热换 jar | 机器能拉 Maven / npm 依赖 |
| jar + systemd | `deploy/kudu-console.service` + `deploy/upgrade.sh` | 无 Docker、只要 JDK | 有 JDK 17 |

### 8.2 持久化

| 路径 | 内容 | 必须挂载 |
| --- | --- | --- |
| `/app/config` | `clusters.json`、`test-schedules.json` | 是，否则重启丢平台配置 |
| `/app/logs` | 应用日志、`auto-update.log` | 是，否则升级后无法查历史 |
| `/app/release` | 热升级 jar（仅源码构建模式） | 按形态 |

挂目录而不是挂单个文件：宿主端文件不存在时 Docker 会把 bind 源建成目录，导致 `java -jar` 失败。

### 8.3 在线升级脚本

`deploy/upgrade.sh` 的四条设计原则，改动时不能破坏：

1. **幂等**：版本相同直接退出 0，随便跑。
2. **校验优先**：下载后比对 `.sha256`，不通过就中止，绝不动线上文件。
3. **原子替换**：备份旧 jar → `install` + `mv`，不出现半个 jar。
4. **失败自愈**：重启后轮询首页 20 次，起不来自动换回备份并退出非 0。

`CHECK_ONLY=1` 只检查不安装（有新版本退出码 10），适合挂 cron 巡检。

### 8.4 公网暴露前置条件

控制台**没有鉴权**，能建表、删表、清空数据。暴露到公网必须同时满足：

- 外层有访问控制（Cloudflare Access 白名单 / Caddy Basic Auth），二者选一且必须配。
- 走 Cloudflare Tunnel 时 `TUNNEL_TOKEN` 只放 `.env`，不进仓库、不写进文档。
- 上线前在「[12.3 上线前](#123-上线前)」逐项打勾。

---

## 9. 安全与合规

### 9.1 提交身份一律匿名

公开仓库的提交不得带真实邮箱（公司、个人都不行）。

```bash
git config user.name "saul"
git config user.email "57277097+999bug@users.noreply.github.com"
```

- 格式：`<GitHub数字ID>+<用户名>@users.noreply.github.com`。
- 首次提交前设置；已有历史提交的清理代价极高（需改写全部 SHA + 强推 + 通知协作者），本项目已付出过一次代价。
- 流水线里的 bot 同样用 noreply：`41898282+github-actions[bot]@users.noreply.github.com`。
- GitHub 侧建议开启 Settings → Emails → 「Keep my email addresses private」与「Block command line pushes that expose my email」。

### 9.2 不入库清单

| 类别 | 具体 | 泄露后果 |
| --- | --- | --- |
| 凭据 | `.env`、`TUNNEL_TOKEN`、ACR 密码、任何 token | 直接被利用 |
| 环境信息 | `config/*.json`（含内网 master 地址） | 暴露内网拓扑 |
| 运行产物 | `logs/`、`data/`、`release/`、`target/` | 仓库膨胀、日志含内部数据 |
| 构建产物 | `src/main/resources/static/`、`web/node_modules/`、`web/dist/` | 与源码不一致 |
| 本地工具产物 | `.idea/`、`.shots/`、`.workbuddy/` | 噪音 |

判断方法：`git status` 里出现不认识的文件，先问「它是不是运行期产生的」，是就进 `.gitignore`，不确定就先不 add。

### 9.3 Secrets 管理

- 只在 GitHub Repository secrets 里配置，**不写进 workflow、不写进 README、不写进聊天记录**。
- 文档一律用占位符：`<crpi-实例域名>`、`<命名空间>`、`<用户名>`。已发生的两次 ACR 故障（域名不一致、末尾换行）都是「凭据靠人手工粘贴」带来的，能自动容错的地方要容错。
- 轮换凭据时先改 ACR 固定密码 → 更新 Secret → 重跑一次 Release 验证，再清理旧密码。

### 9.4 文档脱敏

公开文档里出现的内网地址、账号、实例域名一律替换成占位符或用保留地址（`192.0.2.0/24`、`example.com`）。部署示例可以写「能连通 Kudu master 的机器」，不必给出真实 IP 段。

---

## 10. Git 安全红线

**绝对禁止**（一次都不行，代价是仓库报废级）：

- `git stash`（含 `push` / `pop` / `-u`）
- `git reset --hard`
- `git checkout -- <file>`（丢弃工作区改动）
- `git clean`
- `git gc` / `git prune` / `git reflog expire`
- `git filter-branch`（唯一例外：用户当次明确授权，且先做 bundle 备份）
- `git rebase`（含 `-i`）
- 直接改写 `.git/` 内部文件（refs / index / objects）
- `git push --force`（历史改写场景需用户单独授权）

**允许**：`add` / `commit` / `push` / `pull --ff-only` / `status` / `log` / `diff` / `ls-remote` / `fetch`。

**需要先确认**：放弃工作区改动、清理未跟踪文件、切分支前有未提交改动、多个会话同时操作同一仓库（此时只做读操作，绝不 stash 或回退别人的文件）。

---

## 11. 人与 AI 的协作约定

同一套规则，对人、对 AI 都成立。

1. **先诊断、后动手**：被问「什么原因」「能不能优化」时只给分析和方案，等明确同意再改。用户说「改」才改。
2. **只 add 具体路径**：`git add <file>`，禁止 `git add -A` / `git add .`——多会话并行时会把别人的半成品卷进提交。
3. **改完自动提交，但绝不推送**：完成实质改动（功能 / 修复 / 文档）后直接 commit，`push` 由用户执行。
4. **提交前给证据**：单测通过数量、`npm run build` 结果、真实服务冒烟结论、前端截图覆盖的宽度。没有证据的「已完成」不算完成。
5. **收尾清理**：停掉本会话起的进程，释放端口；交付总结里说明改了哪些文件、验证了什么、还有什么没验证。
6. **回复用中文，简洁**，不写 AI 署名，不用夸张措辞。
7. **不擅自扩大范围**：修一个 bug 不顺手重构三个模块；发现文档漂移先报告，等确认再改。

---

## 12. 检查清单

### 12.1 提交前

- [ ] `mvn -B test` 通过
- [ ] `cd web && npm run build` 通过，`src/main/resources/static/index.html` 存在
- [ ] 涉及接口 / 破坏性操作：真实服务（9099）上验证过成功与拒绝两条路径
- [ ] 涉及布局：1440 / 820 / 700 三个宽度都看过截图
- [ ] README 对应章节已同步
- [ ] `git status` 里没有 `.env`、`config/*.json`、`logs/`、`target/`、`static/`
- [ ] 提交信息符合 [3.2](#32-提交信息规范)，作者是 `saul` + noreply 邮箱

### 12.2 发版前

- [ ] `main` 上 CI 绿
- [ ] 版本号未被占用（手填时）
- [ ] 破坏性改动已在真实集群回归
- [ ] `release.yml` 与 `Dockerfile` 本次未做未验证的改动
- [ ] ACR Secrets 有效（要同步国内时）
- [ ] 回滚路径已确认（上一个 tag 可拉）

### 12.3 上线前

- [ ] 机器能连通 Kudu master 的三个 RPC 端口
- [ ] `curl /api/cluster/status` 返回集群信息，不是 500
- [ ] 公网暴露前已加 Cloudflare Access 或 Basic Auth
- [ ] `config/`、`logs/` 已挂载持久化
- [ ] `JAVA_OPTS` 已按机器规格设置上限
- [ ] `CHECK_ONLY=1 ./deploy/upgrade.sh` 试跑过
- [ ] `curl /api/meta/version` 的 `commit` 与预期版本一致

### 12.4 新项目初始化

- [ ] 仓库可见性、Pages / Packages 策略已定
- [ ] git 身份为匿名，第一次提交前设置
- [ ] `.gitignore` / `.gitattributes` / `.dockerignore` 三件套到位
- [ ] 目录骨架与分层边界确定
- [ ] `build` / `start` 脚本双平台可跑
- [ ] `.env.example` 列全变量
- [ ] `ci.yml` 跑通
- [ ] `release.yml` 跑通首次发布
- [ ] README / DEPLOY / DEVELOPMENT 三份文档立起来
- [ ] 版本号注入链路（build-args → ENV → `/api/meta/version`）打通

---

## 附录 A 本机环境速查

| 项 | 值 / 说明 |
| --- | --- |
| JDK 17 | `C:/Program Files/Java/jdk-17.0.16+8`（PATH 里没有 `java`，必须设 `JAVA_HOME`） |
| Maven | `C:/Users/SERVER/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn` |
| Node / npm | v22.22.2 / 10.9.7（CI 用 Node 20，本地不碰新语法就不会岔开） |
| Docker | **未安装**，镜像构建只在 CI 验证 |
| 自测端口 | 9099（应用默认 8088，别占） |
| Shell | Git Bash（POSIX 语法），Windows 专属操作走 PowerShell |
| 特殊变量 | 本机存在 `SERVER__PORT`，会覆盖 Spring Boot `server.port`，起服务一律显式 `--server.port=` |
| 批处理编码 | `.bat` 只用 ASCII，中文会解析失败 |

## 附录 B 常见故障

| 现象 | 原因与处理 |
| --- | --- |
| `mvn package` 报 jar 被占用 | 上次 `start.bat` / 自测实例还在跑，先停进程 |
| 页面白屏或 404 | `static/` 没构建（`npm run build` 未跑或顺序颠倒）；或新增路由漏了 SPA 回退 |
| 端口不是 8088 | `SERVER__PORT` 等环境变量覆盖，显式传 `--server.port` |
| `docker pull` 返回 `denied` | ghcr 包是私有的：Packages → Package settings → Public |
| ACR 403 / `invalid control character` | 见 [7.6](#76-报错对照) |
| 版本号没变 | 镜像没换；对比 `/api/meta/version` 的 `commit` 与仓库 HEAD |
| 集群 60s 超时 | master 地址不通或 Kudu RPC 被拦，先 TCP 探活再查网络策略 |
| Linux 上 `.sh` 报 `bad interpreter` | 检出时换行符成了 CRLF，检查 `.gitattributes` 是否生效（`git check-attr`） |
| Actions 上前端产物缺失 | `npm build` 与 `mvn package` 顺序反了，或 `outDir` 被改 |

## 附录 C 文档维护矩阵

| 事实 | 唯一权威文档 | 其他文档只放 |
| --- | --- | --- |
| 功能、REST 接口、默认值、限制 | README | — |
| 部署形态、目录挂载、公网访问、离线包 | DEPLOY.md | README 放最短路径 + 链接 |
| 镜像仓库、Secrets、国内同步、报错对照 | ALIYUN-ACR.md | README 一句「国内见链接」 |
| 初始化、提交、自测、流水线、发布纪律 | DEVELOPMENT.md | README 的「维护约定」指向本文 |

同一事实只在一处写全，其它地方给链接。发现两处说法不一致，以代码为准，改文档。

## 附录 D 当前已知偏差（待确认，未改）

以下问题在写本文时核对代码发现，尚未修改，逐条确认后再动：

1. **ALIYUN-ACR.md 第三节表格**仍写「push 代码 `git push origin main` → 自动 `v1.0.<run_number>`」，与现行 `release.yml`（push main 只跑 CI、不发版）矛盾，应改为「手动触发 / 打 tag」。
2. **docker-compose.prod.yml 顶部注释**引用 `.github/workflows/docker-image.yml`，该文件已并入 `release.yml`，注释过期。
3. **内网地址**：README、DEPLOY.md、`.env.example` 中的 `192.168.46.23:7051` 等为测试集群示例地址，是否保留需确认（仓库公开，介意内网拓扑则换占位符）。
4. **tag 类型**：`v1.0.1` ~ `v1.0.11` 均为 lightweight tag，非 annotated。发版 tag 建议 `git tag -a vX -m "..."`，但流水线自动补 tag 目前是轻量的，两侧需统一。
5. **手填版本号无校验**：`release.yml` 未拦截「tag 已存在」的情况，`--clobber` 会覆盖同版本 Release 附件，建议加一步 `git rev-parse "$V"` 判重。
6. **本地无法验证镜像**：本机无 Docker，Dockerfile 改动只能靠 CI 与发版后确认，属已知限制。
