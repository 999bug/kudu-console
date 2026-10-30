# 项目档案（project-profile）

> 每个项目只改这一个文件。这里的事实优先于通用规范；通用规范不写项目事实。

## 1. 基本信息

```yaml
project: kudu-console
type: oss                         # GitHub 开源项目
visibility: public
description: 测试环境用的 Apache Kudu 管理台，单进程提供 REST 与页面，Docker 一条命令部署
```

## 2. 技术栈

```yaml
languages:
  java: 17                        # pom.xml: java.version=17
  vue: 3                          # web/package.json: vue ^3.5.13 + vite ^5.4.11
framework: spring-boot 3.3.12     # + org.apache.kudu:kudu-client 1.17.1
package_manager: maven            # 前端用 npm，锁文件 web/package-lock.json 入库
build_command: ./build.sh         # Windows 用 build.bat；内部完成 npm build + mvn clean package
test_command: mvn -B test         # 本机无全局 mvn / java，需先设 JAVA_HOME，见 DEVELOPMENT.md 附录 A
dev_command: java -jar target/kudu-console.jar --server.port=9099
```

## 3. 提交身份与策略（oss）

```yaml
git_user_name: saul
git_user_email: 57277097+999bug@users.noreply.github.com
enable_private_email: true
```

```yaml
commit_style: conventional        # 历史提交为 feat: / docs: ，不中途换风格
                                  # 要切公司 [NF]/[BF]/[IM] 风格，改这一行并同步 commit.md 的执行口径
issue_tracker: GitHub Issues      # 无禅道 / Bugzilla，单号用 Closes #123
auto_commit: true
push_policy: on-request           # 提交自动，push 等明确授权
```

## 4. 交付与发布

```yaml
deploy_target: docker-compose     # 主用 docker-compose.prod.yml；备选 jar + systemd
release_trigger: manual           # Actions 手动 workflow_dispatch，或 git push origin vX
version_scheme: v1.0.<run_number>
registry: ghcr.io/999bug/kudu-console
domestic_mirror: 阿里云 ACR       # 可选，配 ACR_REGISTRY/ACR_NAMESPACE/ACR_USERNAME/ACR_PASSWORD 才同步
pages: no                         # JVM 服务 + 需要连通内网 Kudu，Pages 不适用
release_assets: kudu-console.jar + .sha256 + kudu-console-image-<tag>.tar.gz
retention: 20                     # Release 只留最近 20 个，tag 全留
```

## 5. 敏感信息

```yaml
secrets_location: GitHub Repository secrets
never_commit:
  - .env（含集群地址与 TUNNEL_TOKEN）
  - config/*.json（含内网 master 地址）
  - logs/ data/ release/ target/
  - src/main/resources/static/（前端构建产物）
internal_endpoints:
  allowed_in_docs: false          # 待确认：README / DEPLOY / .env.example 仍有 192.168.46.23 示例地址
```

## 6. 作用域与验证

```yaml
ai_scope:                         # AI 只看 / 只改这些目录
  - src/main/java
  - src/test/java
  - web/src
  - tools
  - README.md / DEVELOPMENT.md / AGENTS.md / .agents
  # 排除：target、src/main/resources/static（构建产物）、web/node_modules、logs、config
log_language: en                  # 现有 48 条日志均为英文，保持
verify_build: ./build.sh
verify_test: mvn -B test
verify_test_policy: full
verify_smoke: KUDU_CONSOLE_URL=http://localhost:9099 python tools/api-smoke.py
verify_version: curl -s http://localhost:9099/api/meta/version
verify_ui: Playwright + 本机 Chromium，1440 / 820 / 700 三档截图
test_port: 9099                   # 别占 8088
```

## 7. 项目自有文档

```yaml
related_docs:                     # 先读这些，优先级高于通用建议
  - DEVELOPMENT.md                # 开发与发布规范：初始化、提交、自测闭环、流水线、四张清单
  - README.md                     # 功能、REST 接口、实现要点与限制
  - DEPLOY.md                     # 三种部署形态、离线包、公网访问、上线检查
  - ALIYUN-ACR.md                 # 国内镜像同步与服务器拉取
```

## 8. 覆盖与例外

```yaml
overrides:
  - rule: 容器内非 root 运行（release-docker 建议）
    deviation: 以 root 运行
    reason: 基础镜像未建非 root 用户，且仅测试环境使用

  - rule: 服务需鉴权
    deviation: 无任何鉴权
    reason: 仅测试环境；公网暴露必须外层加 Cloudflare Access 或 Basic Auth

  - rule: 发布 tag 用 annotated
    deviation: v1.0.1~v1.0.11 均为 lightweight tag，流水线自动补 tag 也是轻量的
    reason: 历史遗留，待统一；不影响回滚（按 tag 名 checkout 仍可用）
```

## 9. 已知的技术债 / 雷区

- **本机无 Docker**：镜像只能在 CI 验证。改 Dockerfile 后必须发版确认镜像能起、`/api/meta/version` 的 commit 正确。
- **本机无全局 java/mvn**：一律用 `build.sh` 或显式 `JAVA_HOME`；`SERVER__PORT` 会覆盖 `server.port`，起服务必须显式 `--server.port=`。
- **手填已存在的版本号会覆盖 Release 附件**（`--clobber`），流水线未判重：重发一律用新 patch 号。
- **多平台下 service 不能有 Spring 单例状态**：所有业务对象随 `ClusterRuntime` 创建。
- **新增页面必须注册 SPA 回退**，否则直接刷新 404（踩过 `/test-ops`）。
