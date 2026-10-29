# 阿里云 ACR 镜像发布与部署指南

本文是 `kudu-console` 使用**阿里云容器镜像服务（ACR）**作为国内镜像源的完整手册，覆盖：

1. 在阿里云开通 ACR 并创建仓库
2. 在 GitHub 配置 Secrets，让 CI 自动把镜像同步到阿里云
3. 触发发布与验证
4. 在服务器 / 虚拟机上拉取镜像、启动服务、自动更新、回滚

整体链路：

```
git push main
   → GitHub Actions 构建 jar + 前端
   → 镜像推送到 ghcr.io（v1.0.x + latest）
   → 自动同步一份到阿里云 ACR（v1.0.x + latest）
   → 服务器 cron 每 5 分钟检查并拉取 ACR 的 latest
   → 容器自动重建，页面顶栏显示新版本号
```

> 文中的 `<crpi-实例域名>`、`<命名空间>`、`<用户名>` 换成你自己的值：
> - 实例域名形如 `crpi-jzu3xxxxxxx.cn-shanghai.personal.cr.aliyuncs.com`（新版个人实例），在 ACR 控制台「访问凭证」页可见；
> - 旧版个人版域名则是 `registry.<地域>.aliyuncs.com`；
> - 不确定就用服务器上 `cat ~/.docker/config.json`，`auths` 下那个 key 就是登录成功的精确域名。

---

## 一、阿里云：开通 ACR 并创建仓库

1. 登录阿里云控制台，搜索「容器镜像服务」，开通**个人版**（免费）。
2. 创建**命名空间**（如 `kuducrud`），一个命名空间下可以放多个仓库。
3. 创建仓库 `kudu-console`：
   - 仓库类型选**私有**；
   - 代码源选「本地仓库」（我们用 CI 推镜像，不需要绑定 GitHub 仓库）；
   - 也可以在命名空间设置里开启「push 自动创建仓库」，省掉手动建仓。
4. 「访问凭证」页 → 设置**固定密码**（不是临时 token，不会过期），并记下：
   - **实例域名**（`<crpi-实例域名>`）
   - **用户名**（个人版就是访问凭证页显示的用户名，不是邮箱/手机号）
   - 固定密码

最终镜像地址的格式：

```
<crpi-实例域名>/<命名空间>/kudu-console:<tag>
```

---

## 二、GitHub：配置 Secrets

仓库 → **Settings → Secrets and variables → Actions** → **Repository secrets** → `New repository secret`，逐个添加四个：

| Secret 名称 | 值 | 说明 |
| --- | --- | --- |
| `ACR_REGISTRY` | `<crpi-实例域名>` | **必须和服务器 `docker login` 的域名完全一致**，裸域名：不带 `https://`、不带命名空间、结尾无 `/` |
| `ACR_NAMESPACE` | `<命名空间>` | 第 1 步创建的命名空间 |
| `ACR_USERNAME` | `<用户名>` | 访问凭证页显示的用户名 |
| `ACR_PASSWORD` | `<固定密码>` | 固定密码，不是阿里云登录密码，也不是临时 token |

注意：

- 加在 **Repository secrets** 下，不是 Environment secrets（工作流没有引用任何 environment）。
- 工作流在运行前会自动去掉这些值里混入的换行 / 空格，但仍然建议粘贴时保持干净。
- GitHub 不会让你回看 Secret 的值；不确定就删掉重新添加一遍。

---

## 三、触发发布与同步

三种触发方式（对应 `.github/workflows/release.yml`）：

| 方式 | 操作 | 版本号 |
| --- | --- | --- |
| push 代码 | `git push origin main` | 自动 `v1.0.<run_number>` |
| 打 tag | `git tag v1.0.9 && git push origin v1.0.9` | 用该 tag |
| 手动触发 | Actions → Release → Run workflow → 分支 `main` → 版本留空 | 自动 `v1.0.<run_number>` |

每次运行会依次：

1. 跑单元测试、构建前端、打包 jar
2. 构建镜像并推送 **ghcr.io**（`<tag>` + `latest`）
3. **Mirror to domestic registry**：登录 ACR，把镜像同步为 `<crpi-实例域名>/<命名空间>/kudu-console:<tag>` 和 `:latest`
4. 推 git tag、发布 Release（jar、sha256、离线镜像包）

验证是否同步成功：

- Actions 里该次运行的 **Mirror to domestic registry** 步骤出现 `已同步到 <crpi-实例域名>/<命名空间>/kudu-console:<tag>`；
- 或阿里云 ACR 控制台里 `kudu-console` 仓库出现 `v1.0.x` 和 `latest` 两个 tag。

说明：

- **同步失败不会阻塞发版**：tag 和 Release 照常产出，失败的步骤会标红并在日志里给出原因；修好 Secrets 后在该次运行上点 `Re-run failed jobs` 即可补同步。
- 该步骤没配 Secrets 时自动跳过，不影响 ghcr 主链路。

常见报错对照：

| 报错 | 原因 |
| --- | --- |
| `login attempt ... failed with status: 403 Forbidden` | 密码不是固定密码 / 用户名不对 / RAM 用户未授权 / 域名与实际实例不一致 |
| `dial tcp: lookup ... no such host` | `ACR_REGISTRY` 值不是一个有效域名（带了 `https://` 或路径） |
| `unable to parse server address: ... invalid control character` | Secret 值末尾混入了换行符（现在工作流会自动去除，一般不再出现） |
| `denied: requested access to the resource is denied` | 仓库不存在且命名空间未开自动建仓，或账号无权限 |

---

## 四、服务器 / 虚拟机：首次部署

### 1. 环境要求

- Docker 20.10+ 与 Compose v2 插件（`docker compose version` 能执行）
- 服务器能访问你的 Kudu 集群 master 端口（如 `172.20.78.176:7051`）

### 2. 准备目录与文件

```bash
sudo mkdir -p /opt/kudu-console/data/config /opt/kudu-console/data/logs
cd /opt/kudu-console
# 把仓库里的 docker-compose.prod.yml 传到这个目录（scp / sftp 均可）
```

### 3. 登录阿里云 ACR

```bash
sudo docker login <crpi-实例域名> --username=<用户名>
# 提示 Login Succeeded 即可；固定密码不会过期
```

注意：**用哪个用户登录，定时任务就要用哪个用户跑**。下面所有命令都假设是 root（`sudo docker login` 即 root 的凭据）。

### 4. 编写 `.env`

```bash
sudo vi /opt/kudu-console/.env
```

```env
HOST_PORT=8088
KUDU_MASTERS=172.20.78.176:7051,172.20.78.176:7151,172.20.78.176:7251
JAVA_OPTS=-Xms1024m -Xmx4g

# 自动跟随新版本用 latest；需要固定 / 回滚时改成具体 tag（如 :v1.0.8）
IMAGE=<crpi-实例域名>/<命名空间>/kudu-console:latest

# 需要公网访问时填 Cloudflare Tunnel 的 token（不用就留空）
TUNNEL_TOKEN=
```

- `KUDU_MASTERS` 是你 Kudu 集群的 master 地址，只影响首次初始化，之后以 `data/config/clusters.json` 为准。
- `.env` 不入库，注意别提交到 git。

### 5. 拉取并启动

```bash
cd /opt/kudu-console
sudo docker compose -f docker-compose.prod.yml pull
sudo docker compose -f docker-compose.prod.yml up -d
```

### 6. 验证

```bash
sudo docker compose -f docker-compose.prod.yml ps                  # 容器 Up (healthy)
curl -s http://127.0.0.1:8088/api/meta/version                     # 返回 version / commit / buildTime
sudo docker inspect kudu-console --format '{{.Config.Image}}'      # 确认镜像地址
```

浏览器打开 `http://<服务器IP>:8088/`，顶栏能看到 `v1.0.x · commit`，就说明部署成功。

---

## 五、日常更新与自动更新

### 手动更新

```bash
cd /opt/kudu-console
sudo docker compose -f docker-compose.prod.yml pull
sudo docker compose -f docker-compose.prod.yml up -d
```

`up -d` 发现镜像变化会自动重建容器，`data/` 里的配置和日志不受影响。

### 自动更新（推荐个人环境）

crontab（root 用户）：

```cron
*/5 * * * * cd /opt/kudu-console && docker compose -f docker-compose.prod.yml pull -q && docker compose -f docker-compose.prod.yml up -d >> data/logs/auto-update.log 2>&1
```

- 前提：`.env` 里 `IMAGE` 用 **latest**，固定 tag 不会被动更新。
- 最多 5 分钟延迟；升级是否生效看顶栏版本号或 `/api/meta/version`。

### 确认版本的三个入口

```bash
curl -s http://127.0.0.1:8088/api/meta/version       # version / commit / buildTime / image
sudo docker inspect kudu-console --format '{{.Config.Image}}'
```

或直接看页面顶栏的版本标签（悬停显示构建时间与镜像）。

### 回滚

`.env` 里把 `IMAGE` 改回上一个 tag（如 `:v1.0.8`），再 `up -d`。历史 tag 在阿里云 ACR 控制台全部保留。

---

## 六、手动同步镜像（备用）

CI 的 ACR 同步失败时（比如 Secrets 还没配好），可以在**任意能同时访问 ghcr 和 ACR 的机器**（比如你的服务器）手动补一份：

```bash
V=v1.0.8
SRC=ghcr.io/999bug/kudu-console:$V
DST=<crpi-实例域名>/<命名空间>/kudu-console

sudo docker pull $SRC
sudo docker tag $SRC $DST:$V
sudo docker tag $SRC $DST:latest
sudo docker push $DST:$V
sudo docker push $DST:latest
```

推完之后按第四节的 `pull` + `up -d` 更新服务器即可。

---

## 七、常见问题

| 现象 | 原因与处理 |
| --- | --- |
| CI 里 ACR 登录 403 | Secret 值与实际登录域名不一致、密码不是固定密码、用户名不是访问凭证页的用户名；逐项对照第二节 |
| `lookup xxx: no such host` | `ACR_REGISTRY` 带了 `https://` 或路径，只保留裸域名 |
| `denied: requested access to the resource is denied` | 命名空间下没有 `kudu-console` 仓库且未开自动建仓；或未 `docker login` |
| 服务器 pull 变慢 / 失败 | 确认 `.env` 的 `IMAGE` 是 ACR 地址；ghcr 在国内不稳定，仅作备用 |
| cron 跑了但没更新 | `IMAGE` 是固定 tag 而不是 `latest`；或 cron 用户与 `docker login` 用户不一致 |
| 页面版本号没变 | `docker compose pull` 后是否真的拉了新层；看 `/api/meta/version` 的 `commit` 是否与仓库最新提交一致 |
| 停止服务 | `sudo docker compose -f docker-compose.prod.yml down`（`data/` 保留，重新 `up -d` 即恢复） |

---

## 附：本文相关的仓库文件

- `.github/workflows/release.yml`：发布与 ACR 同步流水线（`Mirror to domestic registry` 步骤）
- `docker-compose.prod.yml`：生产编排，镜像来自 `${IMAGE}`
- `.env.example`：`.env` 模板
- `DEPLOY.md`：ghcr 直连、离线部署、公网访问（Cloudflare Tunnel）等更完整的部署方案
