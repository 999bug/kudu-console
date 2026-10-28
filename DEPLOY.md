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
