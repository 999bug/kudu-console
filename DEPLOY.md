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
git clone https://github.com/999bug/kudu-crud.git
cd kudu-crud
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
  https://github.com/999bug/kudu-crud/releases/latest/download/kudu-console.jar

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

## 目标机器上不了外网 / 访问不了 GitHub

**不要在目标机器上 clone 源码构建**——那台机器既要装 JDK + Node + Maven，拉 Maven 依赖时照样会卡在网络上。正确做法是在有网的机器上拿构建好的产物，只把产物传过去。

先看目标机器有什么：

| 目标机器环境 | 拿什么 | 怎么传 |
| --- | --- | --- |
| 有 JDK 17 | `kudu-console.jar` | scp / 共享目录 / U 盘 |
| 有 Docker，能连 registry | 直接 pull 镜像 | `docker pull` |
| 有 Docker，完全断网 | 镜像离线包 `tar.gz` | 下载后 `docker load` |

产物都在 Release 页：`https://github.com/999bug/kudu-crud/releases`

### A. 只需 JDK：直接跑 jar

```bash
# 有网的机器上下载
curl -fL -O https://github.com/999bug/kudu-crud/releases/latest/download/kudu-console.jar

scp kudu-console.jar user@目标机:/opt/kudu-console/

# 目标机器
cd /opt/kudu-console
java -jar kudu-console.jar --server.port=8088 \
  --kudu-console.masters=192.168.46.23:7051,192.168.46.23:7151,192.168.46.23:7251
```

长期跑用前面「方式 C」的 systemd unit，把 `ExecStart` 指到这个 jar 即可。

### B. 能连 ghcr.io：直接拉镜像

镜像由 `.github/workflows/docker-image.yml` 在打 tag 时自动构建推送，内含 JRE、前端页面和全部依赖。

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
curl -fLO https://github.com/999bug/kudu-crud/releases/download/v1.0.1/kudu-console-image-v1.0.1.tar.gz

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
git clone https://ghfast.top/https://github.com/999bug/kudu-crud.git
git clone https://ghproxy.net/https://github.com/999bug/kudu-crud.git
git clone https://kkgithub.com/999bug/kudu-crud.git
```

或者在有网的机器上打成 bundle 带过去，这样走的是完整 git 历史：

```bash
git bundle create kudu-crud.bundle --all
scp kudu-crud.bundle user@目标机:/tmp/
# 目标机器
git clone /tmp/kudu-crud.bundle kudu-crud
```

再次提醒：clone 只解决"拿到源码"，构建仍要 JDK、Node、Maven 和能访问 Maven 中央仓库的网络。内网环境请优先用 A / B / C。

---

## 在线升级

思路和前端项目的 Service Worker 静默更新一致：**版本比对 → 校验通过才落盘 → 起不来自动回滚**。

### 发版

```bash
git tag v1.0.1
git push origin v1.0.1
```

push tag 触发 `.github/workflows/release.yml`：跑测试 → 构建前端 → 打包 → 上传 `kudu-console.jar` 和 `.sha256` 到 Release。

也可以手动：Actions → Release → Run workflow → 填 `v1.0.1`。

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

### Docker 场景

`docker-compose.yml` 把宿主机的 `release/` 目录挂到容器 `/app/release`，`upgrade.sh` 会把新 jar 落到那里，所以升级流程完全一样：

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
