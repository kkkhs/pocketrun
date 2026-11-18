---
sidebar_position: 91
sidebar_label: 参考：工程与发布
title: 第 0 章：运行与发布教程站
description: 从现有仓库运行网站，检查修改，再通过 PR 发布一次更新。
---

本章要完成一次可验证的教程更新：**本地看见修改，PR 检查通过，合并后在线确认。**

现有网站使用 Docusaurus 3.10.2 默认 Docs 主题。先运行它，再走更新流程。

## 本地打开

准备 Git、Node 24.18.0 和 npm。使用 nvm 时，仓库的 `.nvmrc` 会指定 Node 版本；也可以用其他方式安装同一版本。

本地体验可用示例仓库 `kkkhs/pocketrun`。发布练习先 Fork 到自己的账号，或使用你有写入权限的同内容仓库；已有示例克隆时，在示例仓库之外的目录开始。

```bash
OWNER=your-github-name # 换成仓库所属账号；仅本地体验可填 kkkhs
REPO=pocketrun # 改名时填实际仓库名
git clone "https://github.com/$OWNER/$REPO.git"
cd "$REPO"
nvm install
nvm use
cd website
npm ci --registry=https://registry.npmjs.org
```

`website/` 是 npm 项目。安装成功应以退出码 `0` 结束，实际依赖由 `package-lock.json` 固定。

## 检查并预览

继续在 `website/` 运行：

```bash
npm run test:toolchain
npm run typecheck
npm run build
npm run audit:ci
npm run serve -- --host 127.0.0.1 --port 3000 --no-open
```

前四项检查分别覆盖工具链、TypeScript、MDX / 链接和依赖审计策略，都应退出 `0`。`audit:ci` 仍阻止所有未豁免的 high / critical 公告；当前唯一临时例外、截止日期和适用边界见下方折叠说明。任一项失败，先排错再提交。

构建产物在 `website/build/`。打开 `http://127.0.0.1:3000/pocketrun/`；按 `Ctrl+C` 结束预览。端口被占用时换一个空闲端口。

现在检查页面：

- 首页是导读，侧栏先列第 1—4 章主线，再列系统概览与工程发布参考。
- 直接打开并刷新 `/pocketrun/chapter-4/`、`/pocketrun/architecture/`、`/pocketrun/chapter-0/`，页面正常。
- CSS / JS 没有 404；切到手机宽度后，正文和导航可读，代码块不撑开整页。

日常写作可以热更新：

```bash
# 在 website/
npm start -- --host 127.0.0.1 --port 3000 --no-open
```

`serve` 读取已有构建，修改后要重新 build。提交前仍需完成四项检查，不能只看开发页面正常。

## 提交一次更新

结束预览后，从 `website/` 回到仓库根目录，创建文档分支：

```bash
cd ..
git switch main
git pull --ff-only
git switch -c docs/update-chapter-0
# 编辑 website/docs/chapter-0.md
```

首次发布自己的 Pages 前，修改 `website/docusaurus.config.ts`（用实际账号和仓库名替换占位值）：

- `url` 改为 `https://OWNER.github.io`，`organizationName` 改为实际 `OWNER`。
- 仓库改名时，同步 `projectName` 为实际 `REPO`、`baseUrl` 为 `/REPO/`；本地预览和深链也使用新路径。

在自己的仓库启用 Actions 工作流，将 Pages Source 设为 **GitHub Actions**，并把 `github-pages` 环境限定为 `main`。

检查修改，再提交：

```bash
cd website
npm run test:toolchain
npm run typecheck
npm run build
npm run audit:ci
cd ..
git status
git diff --check
git diff -- website/docs/chapter-0.md website/docusaurus.config.ts
git add website/docs/chapter-0.md website/docusaurus.config.ts
git commit -m "docs: clarify chapter 0 validation"
git push -u origin docs/update-chapter-0
```

确认 diff 只包含本次修改，再创建 PR：base 仓库选择自己的 `OWNER/REPO`，分支选择 `main`。暂存检查配置在根目录 `lint-staged.config.cjs`。

## 确认更新上线

PR 只运行检查，不上传 Pages 产物、不部署。合并到自己的 `main` 后，工作流发布 `website/build/`；手动发布也只允许 `main`。

工作流使用 GitHub 内置凭据，无需另填个人 token 或 SSH 密钥。

更新后在自己的站点 `https://OWNER.github.io/REPO/`（替换为实际账号和仓库名）确认：

1. PR 的检查通过，且没有部署步骤执行。
2. 合并后的 Actions 完成部署。
3. 首页、章节和参考页深链能打开，手机宽度可读、静态资源正常。
4. 页面上确实出现本次新文案，再记录提交和 Actions 运行。

**绿色状态不能代替页面验证。** 以自己的站点上出现新文案为准。

## 遇到问题

| 现象 | 先检查 |
| --- | --- |
| 安装失败 | Node 版本、公共 registry、package.json 与 lockfile 是否一致 |
| MDX / 断链报错 | 报错行、文档文件名、代码块是否闭合；尖括号示例放进代码 |
| 预览仍是旧文案 | 修改后是否重新 build，serve 是否指向当前构建 |
| 页面或资源 404 | 访问路径与 `url` / `baseUrl` 是否对应 |
| Pages 拒绝发布 | 是否在 main，Pages 来源与环境分支规则是否正确 |

练习：在练习分支写错一个文档相对链接，确认 build 失败；修复并重新构建。然后补充一条有用的说明，走完更新流程。

<details>
<summary>工程位置与发布配置</summary>

- 正文：`website/docs/`；配置：`website/docusaurus.config.ts`、`sidebars.ts`。
- 版本：根目录 `.nvmrc` 和 `website/package-lock.json`。
- 工作流：`.github/workflows/pages.yml`；工具链检查：`website/toolchain-compat.cjs`。
- `node_modules/`、`.docusaurus/` 和 `build/` 是本地产物，不提交。

项目型 Pages 的关键字段：

| 字段 | 示例仓库当前值 |
| --- | --- |
| url | `https://kkkhs.github.io` |
| baseUrl | `/pocketrun/` |
| trailingSlash | `true` |
| organizationName / projectName | `kkkhs` / `pocketrun` |
| Docs routeBasePath / 导读 slug | `/` / `/` |

正文用 `architecture.md` 这类相对链接，Docusaurus 会生成项目路径。

本工作流不自动启用 Pages。

构建权限为 `contents: read`，部署另需 `pages: write`、`id-token: write`。固定 Action 版本和完整规则见[工作流源码](https://github.com/kkkhs/pocketrun/blob/main/.github/workflows/pages.yml)。

`pages` 并发组不会取消运行中的任务，但只保留一个待运行任务，不是逐提交发布的 FIFO 队列。

</details>

<details>
<summary>审计记录与安装警告</summary>

2026-11-13 的基线中，原始 `npm audit` 退出 `1`，报告 5 个 low 节点（1 条 GHSA）；`--audit-level=high` 退出 `0`。

对应的[官方 CVE 记录](https://www.cve.org/CVERecord?id=CVE-2026-93753)已于 2026-11-02 将 CVE-2026-93753 标记为 `REJECTED`，理由是“不描述安全漏洞”。npm 审计源仍保留条目，不能把门禁通过说成审计无条目。

npm 11 可能提示 `@swc/core`、`core-js`、`fsevents` 的 `allow-scripts` 警告。默认情况下，未审查脚本仍可运行；警告不表示已阻止执行。需要调整策略时，逐项核对脚本用途。

2026-11-18，npm 新增 [GHSA-fc5q-39xc-vjpf](https://github.com/advisories/GHSA-fc5q-39xc-vjpf)：所有已发布 picomatch 版本（截至 4.0.7）都被标为 high，且暂无修复版本。触发条件是把攻击者控制的 repeated-extglob **pattern** 交给 picomatch；PocketRun 只在构建 / 测试工具中使用仓库固定的 glob pattern，不接受远程 pattern 输入。

`scripts/audit-ci.cjs` 因此只临时排除这一条精确 source / URL / range，仍会阻止任何其他 high / critical 根公告；公告字段变化也会失败。例外在 **2026-12-18** 自动过期，届时必须重新检查修复版本，不能永久静默忽略。原始 `npm audit` 仍可用于查看全部传播节点。

</details>

从空目录搭同类网站可参考[Docusaurus 官方安装指南](https://docusaurus.io/docs/installation)；本项目使用 classic TypeScript 模板。另见[TypeScript 支持](https://docusaurus.io/docs/typescript-support)、[部署与 baseUrl](https://docusaurus.io/docs/deployment)、[GitHub Pages 工作流](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)。

## 本章代码

- [PR #1：教程阅读与发布流程](https://github.com/kkkhs/pocketrun/pull/1)
- [Release `chapter-0`：本章结束时的源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-0)
