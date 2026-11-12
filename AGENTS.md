# AGENTS.md

本文件是指针，不是规范本体。规范装在全局目录，不随本仓库分发。

## 规范在哪

```bash
printf '%s\n' "${AI_DEV_STANDARDS_HOME:-$HOME/.ai-dev-standards}"
```

路径可能被环境变量改过。**用上面这条命令取，不要凭记忆拼绝对路径**——写死了换台机器就失效。

## 开工顺序

1. 读规范入口。模块路径都相对它所在目录：

   ```bash
   cat "${AI_DEV_STANDARDS_HOME:-$HOME/.ai-dev-standards}/AGENTS.md"
   ```

2. 读本仓库 `.agents/project-profile.md`。它是项目事实的唯一来源，优先级高于通用规范。
3. 按规范第 3 节分流表，只加载本次任务需要的模块，不要一次全读。
4. profile 里仍是「未定」的项必须问用户，不许拿示例值当事实。

## 读不到时

目录不存在或没有读权限：停下，告诉用户「全局规范未安装，请运行 `/ai-dev-standards:install`」。

- 不要凭记忆执行这套规范
- 不要在本仓库补建 `.agents/` 模块目录
- 不要自己找一份替代品顶上

## 优先级

用户本次会话的指令 > `.agents/project-profile.md` > 全局 `AGENTS.md` 与模块 > 你的默认习惯。
