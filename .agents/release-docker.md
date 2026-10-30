# Docker 发布与部署规范

**加载时机**：改 `Dockerfile`、`docker-compose*.yml`、镜像仓库地址、部署脚本；或要交付"能跑起来"的环境。
**边界**：本模块只管**镜像怎么造、怎么跑、怎么换版本**。什么时候发版、版本号怎么定，看 `release-github.md`。

## 1. 镜像命名与 tag

- **【默认】格式**：`<registry>/<namespace>/<image>:<version>`，另推一个 `:latest` 供自动更新使用。
- **【红线】版本 tag 不可变**：同一个 tag 不覆盖重推（覆盖后无法判断线上跑的是哪个 commit）。
- **【默认】镜像名与仓库名保持一致**，不要一个叫 `foo-crud` 一个叫 `foo-console`——部署时会反复写错。
- **【默认】生产用固定版本号，`latest` 只给自动更新场景**。用 `latest` 就必须配自动拉取，否则"升级"是个随机事件。

## 2. Dockerfile

- **【默认】多阶段构建**：构建依赖（编译器、包管理器、node_modules）不进最终镜像。
- **【默认】COPY 顺序按变更频率从低到高**：先拷依赖清单并安装依赖，再拷源码。否则每次改一行代码都要重装依赖。
- **【默认】分层利用语言生态的机制**：Spring Boot 用 `layertools` 拆依赖/业务层；Node 先 `npm ci` 再拷源码；Python 先 `pip install -r` 再拷代码。
- **【默认】构建信息用 build-args 注入**：`APP_VERSION` / `GIT_COMMIT` / `BUILD_TIME`，同时写 ENV 与 OCI label。**不传时要能构建成功**（落到 `dev` / `unknown`）。
- **【默认】HEALTHCHECK 打不依赖外部系统的端点**（首页、`/healthz`），不要打"需要连数据库才返回 200"的接口——否则下游抖动会把容器判定为死亡。
- **【默认】基础镜像版本固定**（`17-jre`、`3.12-slim`），不用 `latest`；同一项目不混用 alpine / debian 变体。
- **【红线】不 `COPY .env`、不 `COPY` 私钥、不在镜像里留下构建期凭据**（多阶段也救不了 `ENV SECRET=...` 留在层里）。
- **【默认】写 `.dockerignore`**：`.git`、构建产物、`node_modules`、日志、本地配置。没有它，构建上下文会大到拖慢每次构建。
- **【建议】容器内非 root 运行**；做不到时在 profile 的 overrides 里写明原因。

## 3. 运行时配置

- **【默认】配置全部走环境变量**，并在 `.env.example` 里列全；真实 `.env` 永不入库。
- **【默认】给 JVM / 运行时内存上限一个保守默认值**（例如 `-Xmx1g`），生产在 `.env` 里放大。没上限的容器会在扫描大表时被 OOMKill。
- **【默认】显式设时区**（`TZ=Asia/Shanghai`），否则日志时间与用户认知差 8 小时。
- **【红线】不把内网地址、token 写进 `Dockerfile` 或 `compose` 的默认值里**；写占位符或留空。

## 4. 持久化

- **【红线】需要留存的数据必须挂卷**：配置目录、日志目录、上传目录。容器重建即丢的数据等同于没做。
- **【默认】挂目录（`./data/config:/app/config`），不挂单文件**。宿主端文件不存在时 Docker 会把 bind 源建成目录，导致应用启动失败——这是最常见的"挂载踩坑"。
- **【默认】日志同时落盘与标准输出**：`docker logs` 看实时，挂载文件查历史。
- **【默认】卷里不放代码**，代码只从镜像来。

## 5. compose 编排

- **【默认】两套文件，职责分开**：
  - `docker-compose.yml`：`build: .`，给本地/内网自建用；
  - `docker-compose.prod.yml`：只有 `image:`，不读源码，服务器不需要 JDK/Node。
- **【默认】端口用变量**（`"${HOST_PORT:-8080}:8080"`），不写死。
- **【默认】`restart: unless-stopped` + `healthcheck` 都要有**，否则崩了不会自愈，挂了没人知道。
- **【默认】可选服务用 `profiles`**（例如隧道、调试面板），默认不启动。
- **【红线】不在 compose 里写明文凭据**，用 `.env` 或 secrets。

## 6. 国内镜像（registry 拉不动时）

- **【默认】主仓 + 国内镜像双推**：国际 registry 为主，国内 registry（阿里云 ACR / 腾讯云 TCR / 华为云 SWR）为备。
- **【默认】国内同步放独立步骤并 `continue-on-error`**：同步失败只标红，不阻塞发版；日志给出具体原因。
- **【默认】同步前对凭据做去空白处理**，并给出常见错误对照（403 = 密码/用户名/域名不对；`no such host` = 地址带了 `https://`；`invalid control character` = 值里有换行）。
- **【默认】服务器 `.env` 的 `IMAGE` 指向国内 registry**，否则"配了镜像源但依然很慢"。
- **【默认】cron 自动更新只能用 `latest`，且 cron 用户必须与 `docker login` 用户一致**，否则静默 401。

## 7. 离线交付

- **【默认】离线包格式**：`docker save | gzip` 成 `image-<version>.tar.gz`，随 Release 发布。
- **【默认】`docker save` 时把短名与完整名一起打包**（`app:v1.2.3` + `registry/ns/app:v1.2.3`），否则导入后 compose 认为是缺镜像，又去拉全量。
- **【默认】离线包在 Release 里给出大小与校验值**，方便对方确认传输完整。

## 8. 部署后验证

```bash
docker compose -f docker-compose.prod.yml ps            # Up (healthy)
curl -s http://127.0.0.1:<port>/<version-endpoint>      # 版本与 commit
docker inspect <container> --format '{{.Config.Image}}' # 实际镜像
docker inspect <container> --format '{{.State.Health.Status}}'
```

- **【红线】不凭 `up -d` 没报错就宣布部署成功**。必须确认版本号变了、健康检查是 healthy。

## 9. 回滚

- **【默认】回滚 = 改 `IMAGE` 的 tag + `up -d`**，不重新构建。
- **【默认】回滚前确认上一版镜像仍在 registry**（保留策略不要清空历史 tag）。
- **【默认】数据卷不随回滚变化**，但要确认旧版本能读新版本写下的配置格式——不兼容时要一起回滚配置。

## 10. 高频坑

| 现象 | 原因 |
| --- | --- |
| 每次改代码都要重装依赖 | COPY 顺序错了，源码在依赖安装之前 |
| 镜像 2GB | 构建阶段产物没清、单阶段构建、把 `node_modules` 拷进去了 |
| 容器启动即退出 | 挂载了单文件但宿主端不存在，Docker 建成目录 |
| 健康检查一直 unhealthy | 打的是依赖外部系统的接口，或 `start-period` 太短 |
| `up -d` 后版本没变 | 镜像没拉成功 / tag 没改 / compose 缓存了旧镜像 |
| 本地没装 Docker 却说"镜像验证过" | 不能验证就明确说"未验证，需 CI 或目标机确认" |
