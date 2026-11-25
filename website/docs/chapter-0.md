---
sidebar_position: 1
sidebar_label: 教程站工程与发布
title: 第 0 章：运行与发布这套教程站
description: 在本地启动 Docusaurus 教程站预览、运行构建与链接检查，并通过 GitHub Actions 发布到自己的 GitHub Pages。
---

本章是独立的网站工程手册——**不需要 Android SDK、模拟器或手机，也不是阅读第 1 章的前置条件。** 如果你想在本地阅读、修改教程，或者把 Fork 出来的版本发布到自己的 GitHub Pages，按下面几步走完即可。

这套站点使用 **Docusaurus 3.10.2**（classic TypeScript 模板），依赖独立放在 `website/` 目录里，与 `app/` 的 Android 工程互不干扰。

## 1. 本地启动与热更新预览

确保本机已安装 Git 和 Node **24.18.0**（根目录 `.nvmrc` 已固定版本）：

```bash
git clone https://github.com/kkkhs/pocketrun.git
cd pocketrun
nvm install
nvm use

cd website
npm ci --registry=https://registry.npmjs.org
npm start -- --host 127.0.0.1 --port 3000 --no-open
```

打开浏览器访问 `http://127.0.0.1:3000/pocketrun/`，编辑 `website/docs/` 下的任意 Markdown / MDX 文件，页面都会实时热更新。按 `Ctrl+C` 停止预览。

### 关键文件位置

- [`website/docs/`](https://github.com/kkkhs/pocketrun/tree/main/website/docs)：所有教程正文与 `assets/` 下的配图。正文里写相对链接（例如 `[第 1 章](chapter-1.md)`），Docusaurus 会自动转换成带 `baseUrl` 的站点链接。
- [`website/sidebars.ts`](https://github.com/kkkhs/pocketrun/blob/main/website/sidebars.ts)：左侧分阶段导航目录。
- [`website/docusaurus.config.ts`](https://github.com/kkkhs/pocketrun/blob/main/website/docusaurus.config.ts)：站点标题、`url`、`baseUrl`、顶部导航栏与代码高亮主题配置。

## 2. 构建与全套静态检查

开发预览正常不等于生产构建无误。提交改动前，在 `website/` 运行完整检查与本地生产预览：

```bash
npm run test:toolchain
npm run typecheck
DOCUSAURUS_NO_PERSISTENT_CACHE=1 npm run build
npm run audit:ci
npm run serve -- --host 127.0.0.1 --port 3000 --no-open
```

这四条检查分别守住不同的关口：
- `test:toolchain`：验证当前 Node / Docusaurus 工具链兼容性（[`toolchain-compat.cjs`](https://github.com/kkkhs/pocketrun/blob/main/website/toolchain-compat.cjs)）。
- `typecheck`：检查网站 TypeScript 配置和组件类型。
- `build`：把正文和静态资源编译到 `website/build/`；配置中已开启 `onBrokenLinks: 'throw'`、`onBrokenAnchors: 'throw'` 与坏图报错，任何断链、错锚点或丢失图片都会直接阻断构建。
- `audit:ci`：依赖高危安全审计门禁（[`scripts/audit-ci.cjs`](https://github.com/kkkhs/pocketrun/blob/main/scripts/audit-ci.cjs)，当前临时例外详情见[实机验收与工程档案](verification-records.md)）。

:::tip 修改正文后记得重新 `build`
`npm run serve` 只托管上一次 `npm run build` 生成在 `website/build/` 里的静态文件，不会热更新。日常写作请用 `npm start`，提交前再用 `build` + `serve` 检查深浅色主题、移动端宽度和所有折叠区。
:::

## 3. 发布到自己的 GitHub Pages

如果你想把修改后的教程发布成自己的在线站点：

1. 先把仓库 Fork 到你自己的 GitHub 账号下，切一条新分支做修改。
2. 编辑 `website/docusaurus.config.ts`，将示例账号替换成你的信息：
   - `url` 改为 `https://<OWNER>.github.io`
   - `organizationName` 改为 `<OWNER>`
   - 如果仓库改了名，同步更新 `projectName` 与 `baseUrl: '/<REPO>/'`
3. 在 GitHub 仓库的 **Settings → Pages** 中，将部署来源设为 **GitHub Actions**，并确认 `github-pages` 环境允许 `main` 分支部署。
4. 向你自己的 `main` 分支提交 PR：PR 阶段工作流（[`.github/workflows/pages.yml`](https://github.com/kkkhs/pocketrun/blob/main/.github/workflows/pages.yml)）只执行检查、不部署；合并到 `main` 后会自动构建并发布到你的 Pages 站点，无需手动配置个人 Token。

## 常见排错

| 遇到什么问题 | 先查哪里 |
| --- | --- |
| `npm ci` 报错 | 检查 `node -v` 是否为 `24.18.0`，以及是否使用了公共 npm registry |
| MDX 编译或断链报错 | 检查报错行号：代码块有没有闭合、尖括号 `<foo>` 在正文里是否漏了反引号、图片/链接相对路径是否拼错 |
| 预览里看到的还是旧内容 | 如果跑的是 `npm run serve`，需要先重新执行 `npm run build` |
| 页面样式或图片 404 | 核对访问 URL 路径是否包含 `docusaurus.config.ts` 里设置的 `baseUrl`（默认 `/pocketrun/`） |
| GitHub Pages 部署被拒 | 确认是从 `main` 分支触发，且仓库已将 Pages Source 设为 GitHub Actions |

## 本章源码

- [Release `chapter-0`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-0) · [PR #1](https://github.com/kkkhs/pocketrun/pull/1) · [实机验收与工程档案](verification-records.md)
