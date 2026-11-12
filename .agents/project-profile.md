# 项目档案（project-profile）

> 每个项目只改这一个文件。写 `未定` 表示还没确认——去确认，不要猜。
> 这里的事实优先于通用规范；通用规范不写项目事实。
>
> 生成方式：install.sh 探测初稿 + detect-profile.sh 补探 + 用户确认（2026-11-12）。
> **升级不会改这个文件**；需要按新模板重整时，手动跑 `/ai-dev-standards:profile`。

## 0. 遇到「未定」怎么办

**【红线】不许猜。** 没填完的 profile 比没有更危险：AI 会拿示例值当事实，一路错下去。

遇到 `未定` 就停下来问，一次问清。只有三种情况可以不等：用户已给出等价信息、该项与当前任务无关、用户说"先按默认走"。

判定标准：**猜错的代价 > 多问一句的成本时，必须问。**

## 1. 基本信息

```yaml
project: kudu-console
type: oss                         # GitHub 开源项目（用户确认 2026-11-12）
visibility: public                # gh repo view 实测 PUBLIC
description: Kudu 管理控制台（测试环境）
```

`type` 决定提交身份、敏感信息尺度、发布链路。下面各节按类型给默认值，不符就显式写出来。

## 2. 技术栈

```yaml
languages:                        # 只填实际用到的，版本写实际值，不写 "8+"
  java: 17
  vue: 3                          # web/ 下 19 个 .vue 文件，vue ^3.5.13 + vite 5
framework: spring-boot
package_manager: maven            # 主语言侧；前端 web/ 用 npm，见 §9
build_command: mvn -B package
test_command: mvn -B test
dev_command: docker compose up
```

## 3. 提交与推送

```yaml
git_user_name: saul
git_user_email: 57277097+999bug@users.noreply.github.com
commit_style: conventional
issue_tracker: GitHub Issues
auto_commit: true                 # 改完自动提交，不必每次问
push_policy: on-request           # on-request（等授权）| never | allowed
```

## 4. 交付与发布

```yaml
deploy_target: docker-compose
release_trigger: tag              # 推 v* 标签触发 release.yml；也支持 Actions 手动（workflow_dispatch，可留空自动取 v1.0.<run_number>）
version_scheme: v1.0.<run_number>
registry: ghcr.io/999bug/kudu-console
pages: no                         # 只有纯静态产物才 yes
```

## 5. 敏感信息

```yaml
secrets_location: GitHub Repository secrets   # ACR_NAMESPACE/ACR_PASSWORD/ACR_REGISTRY/ACR_USERNAME/GITHUB_TOKEN；本地另有 .env.example
never_commit:                     # 除通用规则外的项目特有项
  - config/*.json                 # 运行时连接配置，含内网环境地址
  - .env                          # 含集群地址与 tunnel token
internal_endpoints:
  allowed_in_docs: false          # 内网地址能否写进公开文档
```

## 6. 作用域与验证

```yaml
ai_scope:                         # 只写目录路径，不写"前端代码"这种描述
  - src
  - web                           # Vue 前端源码
log_language: zh                  # 最近 20 条提交 subject 全中文
verify_build: mvn -B package      # 2026-11-12 实跑通过
verify_test: mvn -B test          # 2026-11-12 实跑通过（57 个测试全绿）
verify_test_policy: full          # full | compile-only（用 compile-only 要在 §8 写原因）
test_port: 9099                   # 自测端口，避开默认端口
# 有冒烟/版本/UI 检查就补：verify_smoke、verify_version、verify_ui，写命令
```

`verify_*` 为空时按通用规范兜底：构建 + 测试 + 起服务实测。填了就必须跑填的那条。

## 7. 项目自有文档

```yaml
related_docs:                     # 先读这些再动手，优先级高于通用建议
  - ALIYUN-ACR.md                 # 阿里云 ACR 发布与部署
  - DEPLOY.md
  - DEVELOPMENT.md
```

## 8. 覆盖与例外

通用规范里不适用或需要偏离的，逐条写「规则 / 偏离 / 理由」。没有写「无」。

```yaml
overrides: []
```

## 9. 已知的雷区

只写会影响判断的那几条：字段名与含义不符、不能碰的模块、必须连真实环境才能跑的测试。

- **`mvn -B package` 不含前端构建。** 前端在 `web/`（npm + Vite），完整构建用 `./build.sh`：先 `npm run build` 产出到 `src/main/resources/static/`，再跑 Maven。只改前端时跑 `mvn package` 不会把新页面打进 jar。
- 包管理器有两个：后端 maven、前端 npm（`web/package.json`）。`package_manager` 记的是主语言侧的 maven。
- 本项目是测试环境用的管理台，连的是真实 Kudu 集群；`config/*.json` 与 `.env` 里有内网地址，不入库（见 §5）。

## 10. 附：从 /init 产物抽取的原文（可选）

需要时把项目原有 `CLAUDE.md` / `AGENTS.md` 的正文按标题降一级追加到这里。
先消化进 §1–§9，再归档；不需要就删掉本节。
