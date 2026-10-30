# GitHub 发布规范

**加载时机**：准备发版、打 tag、改 `.github/workflows/*`、配 Release / Pages / Secrets。
**边界**：本模块只管**什么时候发、发什么版本、发到哪、怎么回滚**。镜像怎么构建、怎么分层，看 `release-docker.md`。

## 0. 动手前

- **【红线】先读现有 workflow**。仓库里已有 `ci.yml` / `release.yml` 就在它上面改，不要另起一条并行流水线（两条同时打 tag 会撞版本号）。
- **【默认】确认三件事再发版**：CI 是否绿、README/文档是否同步、回滚路径是否明确。任一不确定就不发。

## 1. 触发策略（选一种，写进 profile）

| 策略 | 适用 | 代价 |
| --- | --- | --- |
| `tag`：`git push origin v1.2.3` 触发 | 开源项目、需要人工把关版本 | 每次发版两条命令 |
| `manual`：Actions 手动 Run workflow | 开源 + 自动版本号，最稳 | 要开网页点一下 |
| `push-main`：每次 push 都发版 | 内部测试环境、连续交付 | Release 噪音大，历史难追溯 |

- **【默认】开源项目用 `manual` 或 `tag`**，不要 push-main 自动发版：每次提交都产出 Release，等于没有版本概念。
- **【红线】同一仓库只允许一条流水线打 tag**。
- **【默认】设 `concurrency`**：CI 用 `cancel-in-progress: true`，发布用 `false`（发版不能互相打断）。

## 2. 版本号

- **【默认】按 profile 的 `version_scheme` 走**，不临时改规则。
- 常见两种：
  - 语义化 `vMAJOR.MINOR.PATCH`：功能加 MINOR，修复加 PATCH，不兼容加 MAJOR。
  - 自动递增 `v1.0.<run_number>`：适合内部工具，不需要维护版本文件。
- **【红线】不复用已存在的版本号**。`gh release upload --clobber` 会直接覆盖旧附件，导致"同一版本两台机器行为不同"。
- **【默认】发版前确认目标 tag 不存在**（`git rev-parse "$V"`），流水线里也应该有这一步。

## 3. tag

- **【默认】发布用 annotated tag**（`git tag -a v1.2.3 -m "..."`），`git describe` 与审计都更可靠；流水线自动补 tag 时至少保证消息可读。
- **【红线】不改历史 tag**，不 `git push --force --tags`。
- **【默认】tag 全保留**，需要清理的是 Release，不是 tag。

## 4. Release 内容

- **【默认】标题用版本号，正文用 `--generate-notes` 自动生成**——这依赖提交信息质量，见 `discipline.md`。
- **【默认】附件至少给两样**：可直接运行的产物（jar / 二进制 / 静态包）+ 校验文件（`.sha256`）。
- **【默认】设置保留数量**（例如最近 20 个 Release），超出的自动删，tag 保留。
- **【红线】不上传构建过程垃圾**（`node_modules`、日志、`.env`、本地截图）。
- **【建议】给升级脚本用的产物保持稳定文件名**（`artifact.jar`、`artifact.jar.sha256`），不要让下载 URL 随版本变形。

## 5. GitHub Pages（仅静态产物）

判断标准一句话：**打出来的东西能不能用静态文件服务器直接提供**。有后端、有数据库、要跑 JVM、要连内网的，一律不适用。

- **【默认】Source 选 GitHub Actions**，不要用 branch 部署（branch 部署会逼你把产物提交进仓库）。
- **【默认】用官方三步**：`actions/configure-pages` → `actions/upload-pages-artifact` → `actions/deploy-pages`，权限 `pages: write` + `id-token: write`。
- **【红线】`base` 必须与访问路径一致**：项目页是 `https://<user>.github.io/<repo>/`，`base` 就得是 `/<repo>/`，否则白屏。
- **【默认】history 路由要复制 `index.html` 成 `404.html`**，否则刷新子路由 404。
- **【默认】产物不入库**，`dist/` 进 `.gitignore`。
- **【红线】不把密钥放进前端**：构建产物里的变量都是公开的。

## 6. 权限与 Secrets

- **【默认】最小权限**：CI 只 `contents: read`；发布才给 `contents: write`（打 tag、建 Release）与 `packages: write`（推镜像）。
- **【默认】Secrets 放 Repository secrets**，除非 workflow 里显式引用了 environment。
- **【红线】凭据不进 workflow 文件、不进 README、不进对话**；文档里一律占位符。
- **【默认】可选步骤用 `continue-on-error: true` + 明确日志**（例如同步国内镜像），让主链路不被支路拖死。
- **【默认】第三方 registry 的凭据先做容错**：粘贴值里混入换行/空格是高频故障，使用前 `tr -d '[:space:]'`。

## 7. 开源项目额外要求

- **【红线】提交身份匿名**：`<GitHub数字ID>+<用户名>@users.noreply.github.com`，并把 GitHub 的 "Keep my email addresses private" 打开。
- **【红线】仓库里不出现内网地址、实例域名、账号、云端资源 ID**；示例用 `192.0.2.x` / `example.com`。
- **【默认】镜像/包发布后设为 public**（否则匿名 `docker pull` 返回 `denied`），或用一次 API 调用自动改可见性。
- **【默认】README 至少要回答**：这是什么、怎么跑起来、有哪些已知限制。
- **【建议】加 LICENSE**，没有许可证的开源仓库在法律上不算开源。

## 8. 发布验证（唯一可信的三条）

1. Release 页面附件齐全、可下载。
2. 部署后访问版本接口 / 页面版本号，`commit` 或 `version` 等于本次发版。
3. 镜像 `docker pull` 后 `docker inspect` 的 digest 与流水线日志一致。

- **【红线】不凭"流水线绿了"宣布发布成功**。绿只说明构建没报错，不说明线上换了版本。

## 9. 回滚

- **【默认】回滚靠改版本号，不靠重新构建**：镜像改回上一个 tag，或升级脚本指定旧 tag。
- **【默认】回滚后同样跑第 8 节的验证**，不要假设回滚一定成功。
- **【建议】回滚路径写在 README 里**，出事时不用猜。

## 10. 高频坑

| 现象 | 原因 |
| --- | --- |
| tag 推上去了但没触发 workflow | `GITHUB_TOKEN` 推的 tag 不会触发新的 workflow（递归保护）；要触发就用用户的 token 手动推 |
| 手填版本号后附件被覆盖 | 复用了已存在的 tag + `--clobber` |
| `docker pull` 返回 `denied` | 包是私有的，或 token 无 `packages: read` |
| Pages 白屏 | `base` 没配子路径 |
| 子路由刷新 404 | 缺 SPA 回退（`404.html`） |
| fork 的 PR 里 Secrets 为空 | 安全设计：fork 不给 secrets，别为此改触发方式 |
