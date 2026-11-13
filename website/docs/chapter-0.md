---
sidebar_position: 3
sidebar_label: 第 0 章：工程与发布
title: 第 0 章：建立工程与发布流程
description: 使用官方 Docusaurus 模板，建立可重复检查、预览与更新的教程站。
---

## 成果目标

这一章先建立学习工程的入口：一个独立 Git 仓库、一套可重复的网站检查，以及区分 PR 验证和正式发布的 GitHub Actions 工作流。

完成实践后，你应能在本地打开三篇教程，解释一次文档修改怎样进入网站，并知道哪些证据才足以说明发布成功。**本章不构建 Android 应用；设备运行与首次线上发布仍需实际执行后验收。**

## 为什么先搭建教程站

后续每章都会同时产生实现、解释和检查。如果文档与代码分散、更新靠手工复制，读者很难判断教程对应哪个版本。

Docusaurus 的默认 Docs 主题已提供侧栏、页内目录和前后篇导航。我们直接使用这些能力，不另写首页组件。Git 保存变化，PR 检查构建，`main` 负责发布：先把反馈链路建立起来，再进入 Android 开发。

## 分步实现

### 1. 固定版本与目录

本工程使用 Node **24.18.0**、npm 和 Docusaurus **3.10.2**。`.nvmrc` 与 CI 读取同一个 Node 版本；依赖的实际版本由 `website/package-lock.json` 固定。

目录如下：

```text
pocketrun/
├── README.md
├── .gitignore
├── .nvmrc
├── .github/workflows/pages.yml
└── website/
    ├── package.json
    ├── package-lock.json
    ├── tsconfig.json
    ├── docusaurus.config.ts
    ├── sidebars.ts
    ├── toolchain-compat.cjs
    └── docs/
        ├── intro.md
        ├── architecture.md
        └── chapter-0.md
```

仓库根目录放版本与发布配置，`website/` 才是 npm 项目。`node_modules/`、`.docusaurus/` 与 `build/` 是本地产物，不应提交。

### 2. 从现有仓库开始

公开仓库创建后，在自己的开发目录执行：

```bash
git clone https://github.com/kkkhs/pocketrun.git
cd pocketrun
nvm install
nvm use
node --version
npm --version
cd website
npm ci --registry=https://registry.npmjs.org
```

`node --version` 应显示 `v24.18.0`。`nvm` 可替换为其他安装方式，前提是最终使用同一个 Node 版本。`npm ci` 根据 lockfile 做干净安装；不要为了让安装通过而先删 lockfile。

如果你想练习从空目录生成工程，请在另一个**不存在且独立的目录**中使用官方生成器，不要覆盖现有学习仓库：

```bash
mkdir pocketrun-practice
cd pocketrun-practice
printf '24.18.0\n' > .nvmrc
nvm install
nvm use
git init -b feat/bootstrap
npm exec --yes --registry=https://registry.npmjs.org \
  --package=create-docusaurus@3.10.2 -- \
  create-docusaurus website classic --typescript --package-manager npm
```

这是官方 classic TypeScript 模板，不是手工搭的应用骨架。生成后才按需要移除模板示例、博客和首页，把真实教程放到 `website/docs/`。这个练习目录不会自动获得 PocketRun 的内容与发布配置。

### 3. 明确站点路由

`website/docusaurus.config.ts` 使用项目型 Pages 配置：

- 站点名为 **PocketRun**，中文课程标题为“PocketRun：从零构建 Android AI 助手”。
- `url` 为 `https://kkkhs.github.io`，`baseUrl` 为 `/pocketrun/`，`trailingSlash` 为 `true`。
- Docs 的 `routeBasePath` 为 `/`，导读的 `slug` 为 `/`，因此导读就是首页。
- 默认语言为 `zh-Hans`，使用默认 Docs 侧栏、页内目录与前后篇导航。
- 断链导致构建失败；模板的 `src/pages/index.tsx` 已移除，避免与导读首页冲突。

正文用文档相对链接，例如 `architecture.md`，不要给每条内部链接手工添加 `/pocketrun/`。Docusaurus 会根据站点路径生成链接。

Markdown 会按 MDX 解析。泛型、占位符或含尖括号的示例放进代码块或行内代码，避免被当成 JSX 标签。

### 4. 检查并预览生产产物

在 `website/` 运行：

```bash
npm run test:toolchain
npm run typecheck
npm run build
npm audit --audit-level=high --registry=https://registry.npmjs.org
npm run serve -- --host 127.0.0.1 --port 3000 --no-open
```

模板实际的类型检查脚本名是 `typecheck`，它执行 `tsc`；Docusaurus 的开发和构建命令不能替代这个检查。`test:toolchain` 用一个 Node 断言检查验证固定依赖在真实调用方下的兼容性，包括线程池、复制缓存、CSS 工作线程和 UUID。它只在系统临时目录创建测试文件，结束后清理。构建成功后产物在 `website/build/`。

依赖审计会显示完整结果，`--audit-level=high` 让高危或严重项以非零退出码阻止 CI 发布。通过门禁与原始审计无条目是不同结果，应核对官方记录与实际调用链。

:::note 本次审计基线

2026-11-13 的验证中，原始 `npm audit` 返回 `1`，仍列出 deepmerge 关联的 5 个 low 节点（1 条 GHSA）；高危门禁返回 `0`。对应的[官方 CVE 记录](https://www.cve.org/CVERecord?id=CVE-2026-93753)已于 2026-11-02 将 CVE-2026-93753 标记为 `REJECTED`，理由是“不描述安全漏洞”。npm 条目数量与官方漏洞状态需分别核对。

:::

在浏览器打开 `http://127.0.0.1:3000/pocketrun/`。绑定 `127.0.0.1` 只供本机访问；端口被占用时换一个空闲端口，不要中断别人的服务。结束预览时按 `Ctrl+C`。

写作时可使用热更新：

```bash
# 仍在 website/
npm start -- --host 127.0.0.1 --port 3000 --no-open
```

提交前仍要重新执行类型检查与生产构建，因为开发页面正常不代表生产路径、静态资源和链接都正确。

### 5. 用分支提交内容

初次从空目录建立仓库时使用 `feat/bootstrap` 制作站点，再建立 `main` 作为发布分支。已有仓库的日常更新从最新 `main` 创建短期分支：

```bash
# 从仓库根目录开始
git switch main
git pull --ff-only
git switch -c docs/update-chapter-0
# 编辑本章的一处内容
cd website
npm run test:toolchain
npm run typecheck
npm run build
npm audit --audit-level=high --registry=https://registry.npmjs.org
cd ..
git diff --check
git diff -- website/docs/chapter-0.md
git add website/docs/chapter-0.md
git commit -m "docs: clarify chapter 0 validation"
git push -u origin docs/update-chapter-0
```

提交前检查 `git status`，确认只包含本次需要公开的文件。提交精确路径，比不检查就添加整个工作目录更容易避免意外内容进入仓库。Git 身份应使用你愿意公开的姓名与邮箱，可以在这个仓库局部配置。

### 6. 设置 Actions 与 Pages

本仓库只有一个 `.github/workflows/pages.yml`：

| 触发条件 | build | 上传 Pages 产物 | deploy |
| --- | --- | --- | --- |
| 面向 `main` 的 PR | 安装、兼容性检查、typecheck、build、审计 | 不执行 | 不执行 |
| 推送到 `main` | 同上 | 上传 `website/build/` | 发布 |
| 在 `main` 手动运行 | 同上 | 上传 `website/build/` | 发布 |
| 在其他分支手动运行 | 同上 | 不执行 | 不执行 |

构建任务只有 `contents: read`。发布任务依赖构建，才获得 `pages: write` 和 `id-token: write`，并使用 `github-pages` 环境与实际部署输出 URL。工作流用内置 `GITHUB_TOKEN`，无需个人 token 或 SSH 发布密钥，也不使用 `gh-pages` 分支。

工作流固定官方 Action 版本：checkout `v7.0.1`、setup-node `v7.1.0`、upload-pages-artifact `v5.0.0`、deploy-pages `v5.0.1`。升级时要检查对应官方 release，而不是从旧示例任意替换版本。

正式发布前，仓库管理员在 GitHub 完成：

1. 创建公开仓库 `kkkhs/pocketrun`，确认默认分支是 `main`。
2. 在 **Settings → Pages → Build and deployment** 将 Source 设为 **GitHub Actions**。
3. 在 **Settings → Environments → github-pages** 限定只从 `main` 部署。
4. 推送 `main`；若首次推送早于 Pages 设置，在设置完成后到 Actions 选择本工作流，在 `main` 手动运行。

工作流不自动启用 Pages，也不要求自定义域名。遇到权限或环境策略错误，先检查仓库设置，不改用更高权限凭据绕过。

发布地址为 `https://kkkhs.github.io/pocketrun/`。**配置了地址并不说明部署已经成功**，需要下一节的实际检查。

## 预期与验证

### 本地检查

安装、类型检查和构建都必须以退出码 `0` 结束。然后在生产预览检查：

- `/pocketrun/` 显示课程导读，而不是模板首页。
- `/pocketrun/architecture/` 与 `/pocketrun/chapter-0/` 可直接打开并刷新。
- 左侧课程导航只有三篇已写页面，页内目录和前后篇链接可用。
- 桌面与手机宽度下正文清晰；代码块能横向滚动，不撑开整页。
- 浏览器没有本地静态资源 404；实际链接仍带正确的项目路径。

记录命令、运行版本、退出状态和预览结果。没有执行的检查明确标为待验证。

### 首次发布检查

正式发布后分别核对：仓库公开属性、默认分支、Pages 来源、Actions 的构建与部署结果，以及浏览器实际访问。不能只看一次绿色状态：还要打开首页、两篇深链与静态资源。

### 验证一次真实更新

1. 新建文档分支，增加一条有意义的排错说明。
2. 提交 PR，确认只构建，不上传 Pages 产物、不执行 deploy。
3. 合并到 `main`，确认自动部署完成。
4. 打开发布网址，找到新说明，并记录对应提交与 Actions 运行。

本地通过、PR 通过、首次上线和更新上线是不同的检查结果。只有真实执行后才能在状态说明中把对应项改成已通过。

## 排错

| 现象 | 先检查 |
| --- | --- |
| `npm ci` 失败 | Node 版本、公共 registry 连通性，以及 package.json 与 lockfile 是否一致 |
| MDX 编译错误 | 报错行是否有未放进代码块的尖括号、未闭合代码块或错误 front matter |
| 断链导致 build 失败 | 文档文件名、相对链接和页面 slug，不要把检查降为忽略 |
| 本地预览仍是旧文案 | `serve` 读取的是 `build/`，修改后先重新 build |
| 首页或静态资源 404 | `url`、`baseUrl` 与访问地址是否对应项目型 Pages 路径 |
| Actions 找不到 lockfile | 安装是否在 `website/`，cache 路径是否为 `website/package-lock.json` |
| Pages 发布被拒绝 | Pages Source、`github-pages` 的分支规则，以及是否在 `main` 运行 |

工作流使用一个全局 `pages` 并发组，不取消正在运行的任务。GitHub 并发机制只保留一个待运行任务，**不是保证每次提交都按顺序发布的队列**；后来的任务可以替换待运行任务。当前适合小型教程站，如果 PR 验证争用明显，再拆分验证与发布并发组。

## 练习与小结

在练习分支中故意写错一个文档相对链接，运行 `npm run build`，观察构建是否失败；修复后再次构建。不要将故意的断链提交到 `main`。

然后补充一条原创排错说明，按照“本地检查 → PR 验证 → 合并发布 → 线上确认”的流程更新。在你自己的验证记录里区分已执行和待执行步骤。

本章完成的是学习工程的反馈链路。下一阶段才开始 Android 应用工程，并为每一项设备能力补上独立的可运行检查。

## 官方参考

- [Docusaurus 安装与官方模板](https://docusaurus.io/docs/installation)
- [Docusaurus TypeScript 支持](https://docusaurus.io/docs/typescript-support)
- [Docusaurus 部署与 baseUrl](https://docusaurus.io/docs/deployment)
- [GitHub Pages 自定义工作流](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)
- [GitHub Actions 并发机制](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
