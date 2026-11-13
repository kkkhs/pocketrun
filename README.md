# PocketRun：从零构建 Android AI 助手

面向熟悉 React / TypeScript 的开发者，逐章学习 Android UI、Agent 工具调用、原生执行与权限边界。

**当前阶段：教程站工程与前三篇内容。Android 应用尚未实现。** 设备运行和 GitHub Pages 首次发布需要分别验收，不能以本地网站构建代替。

## 阅读入口

- [课程导读](website/docs/intro.md)
- [系统概览](website/docs/architecture.md)
- [第 0 章：建立工程与发布流程](website/docs/chapter-0.md)

后续应用开发章节为计划，按实际实现与验证进度补充。

仓库地址：<https://github.com/kkkhs/pocketrun>

计划发布地址：<https://kkkhs.github.io/pocketrun/>（首次线上发布待验收）

## 本地运行

需要 Git、Node **24.18.0** 及随该版本提供的 npm。网站使用官方 Docusaurus **3.10.2** classic TypeScript 模板和默认 Docs 主题。

```bash
git clone https://github.com/kkkhs/pocketrun.git
cd pocketrun
nvm install
nvm use
cd website
npm ci --registry=https://registry.npmjs.org
npm run test:toolchain
npm run typecheck
npm run build
npm audit --audit-level=high --registry=https://registry.npmjs.org
npm run serve -- --host 127.0.0.1 --port 3000 --no-open
```

`nvm` 不是必需工具：也可以自行安装 `.nvmrc` 指定版本，再从 `cd website` 继续。克隆命令需在公开仓库创建后使用。

打开 <http://127.0.0.1:3000/pocketrun/>。生产预览使用已有 `build/`，修改文档后需重新构建。日常写作可以运行：

```bash
# 在 website/ 中
npm start -- --host 127.0.0.1 --port 3000 --no-open
```

## 更新流程

```bash
# 在仓库根目录
git switch main
git pull --ff-only
git switch -c docs/update-chapter-0
# 编辑 website/docs/ 中对应章节
cd website
npm run test:toolchain
npm run typecheck
npm run build
npm audit --audit-level=high --registry=https://registry.npmjs.org
cd ..
git diff --check
git add website/docs/chapter-0.md
git commit -m "docs: clarify chapter 0 validation"
git push -u origin docs/update-chapter-0
```

向 `main` 提交 PR：工作流运行安装、工具链兼容性检查、类型检查、生产构建与依赖审计，高危或严重漏洞会阻止发布。合并到 `main` 后自动上传构建产物并发布；手动发布也只允许 `main`。仓库管理员需先将 Pages Source 设为 **GitHub Actions** 并配置 `github-pages` 环境，工作流不自动启用 Pages。

完整步骤和验收清单见[第 0 章](website/docs/chapter-0.md)。公开内容只包含原创教程和通用工程配置，涉及设备操作时仅使用自己拥有或明确获授权的设备与测试数据。
