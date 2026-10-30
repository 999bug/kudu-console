# 部署与在线升级

## 先明确前提

`kudu-console` 是一个 **Java 进程 + Kudu 客户端**，不是静态站点。它必须运行在**能访问 Kudu master（默认 `192.168.46.23:7051/7151/7251`）的机器上**。

这意味着 GitHub Pages / Vercel / Netlify 这类静态托管**无法**部署它：它们跑不了 JVM，更连不到你的内网。GitHub 在这里承担两个角色 —— 代码托管 + 自动出包。

三种上线方式：

| 方式 | 需要什么 | 访问范围 | 说明 |
| --- | --- | --- | --- |
| A. 内网直连 | 一台内网机器（Linux / Windows 均可） | 同一内网的同事 | 最简单，不碰公网 |
| B. Cloudflare Tunnel | 内网机器 + Cloudflare 账号 | 公网（可强制登录） | **推荐**，不用公网 IP、不用开端口 |
| C. 公网 IP + 反代 | 有公网 IP 且能路由到内网的服务器 | 公网 | 必须自己加 HTTPS 和鉴权 |

> ⚠️ 控制台本身**没有登录鉴权**，能建表、删表、清空数据。任何暴露到公网的方式，都必须在外层加访问控制（本文给两种）。

---

## 方式 A / B：Docker 部署

### 1. 准备机器

Linux（或装了 Docker Desktop 的 Windows / macOS），能 `curl` 通 Kudu master：

```bash
curl -sv telnet://192.168.46.23:7051   # 能建立连接即可，Ctrl+C 退出
```

### 2. 拉代码

```bash
git clone https://github.com/999bug/kudu-console.git
cd kudu-console
cp .env.example .env
vi .env                  # 改 KUDU_MASTERS
```

### 3. 起服务

```bash
docker compose up -d --build
docker compose logs -f kudu-console
```

首次构建要下载 Maven 依赖，约 3~8 分钟；之后改代码重建会走缓存。

### 4. 验证

```bash
curl -s http://127.0.0.1:8088/api/cluster/status | head -c 300
```

浏览器打开 `http://<机器IP>:8088/`，集群状态页能看到 LEADER / FOLLOWER 就说明通了。

---

## 方式 B：暴露到公网（Cloudflare Tunnel）

不需要公网 IP，也不需要路由器开端口，流量走 Cloudflare 的出站隧道。

1. Cloudflare Zero Trust → Networks → Tunnels → 创建 Tunnel，复制 token。
2. 填进 `.env`：`TUNNEL_TOKEN=eyJh...`
3. 启动带 tunnel 的编排：

```bash
docker compose --profile tunnel up -d
```

4. 在 Tunnel 的 Public Hostname 里加一条：
   - Subdomain: `kudu`
   - Domain: 你的域名
   - Service: `http://kudu-console:8088`

5. **务必加 Access 策略**（Zero Trust → Access → Applications → Add）：
   - 类型选 Self-hosted，域名填 `kudu.你的域名`
   - Policy 选 Allow，规则用 `Emails` 限定到具体几个人

没有这一步，等于把数据库管理台挂公网裸奔。

---

## 方式 C：jar + systemd（不用 Docker）

```bash
# 1. 建用户和目录
sudo useradd -r -s /sbin/nologin kudu
sudo mkdir -p /opt/kudu-console/logs
sudo chown -R kudu:kudu /opt/kudu-console

# 2. 取最新 jar（也可以用 deploy/upgrade.sh 首次安装）
cd /opt/kudu-console
sudo curl -fL -o kudu-console.jar \
  https://github.com/999bug/kudu-console/releases/latest/download/kudu-console.jar

# 3. 装服务
sudo cp deploy/kudu-console.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now kudu-console

# 4. 看日志
sudo journalctl -u kudu-console -f
```

有公网 IP 时用 Caddy 加 HTTPS + 密码：

```bash
sudo caddy hash-password --plaintext '换成你的密码'
```

`/etc/caddy/Caddyfile`：

```
kudu.example.com {
    basic_auth {
        admin $2a$14$上面生成的哈希
    }
    reverse_proxy 127.0.0.1:8088
}
```

```bash
sudo systemctl reload caddy
```

---

## 从源码构建（开发 / 没有现成镜像时）

环境要求：JDK 17；仅构建时需要 Maven 3.6+、Node.js 18+。

### Windows

```bat
:: 直接启动（默认 8088；target\kudu-console.jar 已构建时无需 Maven/Node，缺失时自动调 build.bat）
start.bat
start.bat 9090

:: 重新构建（前端 npm build + mvn package）
build.bat
```

### Linux / macOS

```bash
chmod +x start.sh build.sh
./start.sh          # 默认 8088
./start.sh 9090
./build.sh
```

### Docker 源码构建

```bash
cp .env.example .env
docker compose up -d --build     # 首次 3~8 分钟，主要在拉 Maven 依赖
```

升级把新 jar 放进 `release/` 再重启，或运行 `./deploy/upgrade.sh`（自动校验 + 失败回滚）。`release/` 为空时容器用镜像内置的 jar 启动。

---

## 目标机器上不了外网 / 访问不了 GitHub

**不要在目标机器上 clone 源码构建**——那台机器既要装 JDK + Node + Maven，拉 Maven 依赖时照样会卡在网络上。正确做法是在有网的机器上拿构建好的产物，只把产物传过去。

先看目标机器有什么：

| 目标机器环境 | 拿什么 | 怎么传 |
| --- | --- | --- |
| 有 JDK 17 | `kudu-console.jar` | scp / 共享目录 / U 盘 |
| 有 Docker，能连 registry | 直接 pull 镜像 | `docker pull` |
| 有 Docker，完全断网 | 镜像离线包 `tar.gz` | 下载后 `docker load` |

产物都在 Release 页：`https://github.com/999bug/kudu-console/releases`

### A. 只需 JDK：直接跑 jar

```bash
# 有网的机器上下载
curl -fL -O https://github.com/999bug/kudu-console/releases/latest/download/kudu-console.jar

scp kudu-console.jar user@目标机:/opt/kudu-console/

# 目标机器
cd /opt/kudu-console
java -jar kudu-console.jar --server.port=8088 \
  --kudu-console.masters=192.168.46.23:7051,192.168.46.23:7151,192.168.46.23:7251
```

长期跑用前面「方式 C」的 systemd unit，把 `ExecStart` 指到这个 jar 即可。

### B. 能连 ghcr.io：直接拉镜像

镜像由 `.github/workflows/release.yml` 在发版时自动构建推送，内含 JRE、前端页面和全部依赖。

```bash
docker run -d --name kudu-console \
  -p 8088:8088 \
  -e KUDUCONSOLE_MASTERS=192.168.46.23:7051,192.168.46.23:7151,192.168.46.23:7251 \
  -v /opt/kudu-console/config:/app/config \
  -v /opt/kudu-console/logs:/app/logs \
  --restart unless-stopped \
  ghcr.io/999bug/kudu-console:latest
```

> 国内直连 ghcr.io 通常很慢或不通。通了就用，不通走下面的离线包。
> 如果 `docker pull` 报 `denied`，说明包还是私有的，去
> GitHub → 个人主页 → Packages → kudu-console → Package settings 改成 Public。

### C. 完全断网：离线镜像包

```bash
# 有网的机器下载（约 400~600 MB）
curl -fLO https://github.com/999bug/kudu-console/releases/download/v1.0.1/kudu-console-image-v1.0.1.tar.gz

scp kudu-console-image-v1.0.1.tar.gz user@目标机:/tmp/

# 目标机器：导入并启动
gunzip -c /tmp/kudu-console-image-v1.0.1.tar.gz | docker load
docker run -d --name kudu-console \
  -p 8088:8088 \
  -e KUDUCONSOLE_MASTERS=192.168.46.23:7051,192.168.46.23:7151,192.168.46.23:7251 \
  -v /opt/kudu-console/config:/app/config \
  -v /opt/kudu-console/logs:/app/logs \
  --restart unless-stopped \
  kudu-console:v1.0.1
```

升级就是重复「下载新包 → 传到目标机 → `docker load` → `docker rm -f kudu-console` → 用新 tag 重新 run」。想少敲命令，把 `docker run` 那串写进一个 `run.sh`。

### D. 仍然想 clone 源码

镜像站（第三方服务，可用性随时变化，能通就用）：

```bash
git clone https://ghfast.top/https://github.com/999bug/kudu-console.git
git clone https://ghproxy.net/https://github.com/999bug/kudu-console.git
git clone https://kkgithub.com/999bug/kudu-console.git
```

或者在有网的机器上打成 bundle 带过去，这样走的是完整 git 历史：

```bash
git bundle create kudu-console.bundle --all
scp kudu-console.bundle user@目标机:/tmp/
# 目标机器
git clone /tmp/kudu-console.bundle kudu-console
```

再次提醒：clone 只解决"拿到源码"，构建仍要 JDK、Node、Maven 和能访问 Maven 中央仓库的网络。内网环境请优先用 A / B / C。

---

## 在线升级

思路和前端项目的 Service Worker 静默更新一致：**版本比对 → 校验通过才落盘 → 起不来自动回滚**。

### 发版

**push 到 main 只会跑测试，不会自动发版。** 需要出新镜像时二选一：

**方式一：Actions 手动触发**（推荐，版本号自动递增）

GitHub 仓库 → Actions → 左侧 Release → 右上 Run workflow → 分支选 `main` → 版本号留空（自动 `v1.0.<run_number>`）或手填 → Run。

**方式二：打 tag**

```bash
git tag v1.0.5 && git push origin v1.0.5
```

`Release` 流水线会依次做完：跑测试 → 构建前端 → 打包 jar → 构建容器镜像 → 推送 ghcr.io → 推版本 tag → 发布 Release（配置了 ACR Secrets 时还会同步到阿里云，详见 ALIYUN-ACR.md）。

产物：

| 产物 | 用途 |
| --- | --- |
| `kudu-console.jar` + `.sha256` | 直接 `java -jar`，或给 `upgrade.sh` 在线升级 |
| `ghcr.io/999bug/kudu-console:<tag>` 与 `:latest` | Docker 部署与升级 |
| `kudu-console-image-<tag>.tar.gz` | 完全离线时 `docker load` |

Release 只保留最近 20 个，更早的自动清理，tag 全部保留。

### 服务器升级

```bash
cd /opt/kudu-console
sudo ./deploy/upgrade.sh
```

脚本做的事：

1. 读 `VERSION` 文件拿当前版本，调 GitHub API 拿最新 tag
2. 版本相同直接退出（幂等，随便跑）
3. 下载 jar 与 `.sha256`，校验不通过就中止，绝不动线上文件
4. 备份旧 jar → `install` + `mv` 原子替换（不会出现半个 jar）
5. 重启服务，轮询 `/api/cluster/status` 20 次
6. **起不来就自动换回备份并重启**，退出码非 0

只检查不安装：

```bash
CHECK_ONLY=1 ./deploy/upgrade.sh   # 有新版本时退出码 10
```

挂 cron 每天凌晨巡检：

```cron
0 3 * * * cd /opt/kudu-console && ./deploy/upgrade.sh >> logs/upgrade.log 2>&1
```

### 回滚

指定任意历史 tag 即可，脚本走同一套下载 + 校验 + 健康检查流程：

```bash
./deploy/upgrade.sh v1.0.0
```

### 方式一：镜像部署（推荐，服务器不需要源码/JDK）

用 `docker-compose.prod.yml`，它只认镜像，不读源码。

首次部署：

```bash
cp .env.example .env && vi .env
docker compose -f docker-compose.prod.yml up -d
```

**以后更新代码分两步：**

1. 推代码（CI 自动跑测试，**不发版**）；要出新镜像按上面「发版」手动触发或打 tag：

```bash
git add <改动文件> && git commit -m "说明"
git push origin main
```

2. 服务器上拉新镜像并重建容器：

```bash
cd /opt/kudu-console
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

**想彻底不用管，交给 cron 全自动**（每 5 分钟检查一次，有新镜像就换）：

```cron
*/5 * * * * cd /opt/kudu-console && docker compose -f docker-compose.prod.yml pull -q && docker compose -f docker-compose.prod.yml up -d >> data/logs/auto-update.log 2>&1
```

注意：cron 方案必须用 `:latest`，固定版本号不会被动更新。如果新版本起不来，容器会反复重启——所以更稳的做法是 cron 只做 `pull`，`up -d` 留给你确认后手动执行：

```cron
*/5 * * * * cd /opt/kudu-console && docker compose -f docker-compose.prod.yml pull -q >> data/logs/auto-pull.log 2>&1
```

`up -d` 发现镜像 digest 变了会自动重建容器；`data/config`、`data/logs` 是挂载卷，配置和日志不会丢。全程不碰源码，也不需要 JDK / Maven / Node。

**生产环境建议固定版本号**，别用 `:latest`，否则每次 `up -d` 都可能在你没预期的时候换版本。在 `.env` 里写：

```env
IMAGE=ghcr.io/999bug/kudu-console:v1.0.2
```

升级就是把这个号改掉再 `up -d`；**回滚就是把号改回上一个版本**，同样一条命令。

**先确认服务器能不能访问镜像仓库：**

```bash
curl -I https://ghcr.io/v2/                      # 只要不是连接超时就算通
docker pull ghcr.io/999bug/kudu-console:latest   # 匿名拉公共包
```

- 返回 `denied` / `unauthorized` → 包还是私有的，去 GitHub 个人主页 → Packages → kudu-console → Package settings 改成 Public
- 连接超时 / reset → 走上一节的离线镜像包，或给 Docker 配代理

### 方式二：jar + systemd

`docker-compose.yml` 把宿主机的 `release/` 目录挂到容器 `/app/release`，`upgrade.sh` 会把新 jar 落到那里，所以升级流程和 systemd 一样：

```bash
cd /opt/kudu-console
sudo ./deploy/upgrade.sh          # 自动识别 docker 模式，替换 jar 后 force-recreate
```

---

## 上线检查清单

- [ ] 机器能连通 Kudu master 的三个 RPC 端口
- [ ] `curl /api/cluster/status` 返回集群信息，不是 500
- [ ] 公网暴露前已加 Cloudflare Access 或 Basic Auth
- [ ] `config/` 目录已挂载持久化（否则重启丢连接配置）
- [ ] `logs/` 已挂载，或确认容器日志可轮转
- [ ] 已设 JVM 上限（默认 `-Xmx1g`），避免大表扫描把机器打满
- [ ] 升级脚本已试跑一次 `CHECK_ONLY=1`
