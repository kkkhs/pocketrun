# PocketRun：从零构建 Android AI 助手

面向 React / TypeScript 开发者，学习让 Agent 在 Android 手机上操作 App、处理文件和执行命令。

教程站已上线；Android 应用尚未实现，应用开发章节按计划推进。

## 阅读教程

- [课程导读](https://kkkhs.github.io/pocketrun/)
- [系统概览](https://kkkhs.github.io/pocketrun/architecture/)
- [第 0 章：运行与发布教程站](https://kkkhs.github.io/pocketrun/chapter-0/)

## 本地运行

使用 Node **24.18.0** / npm，网站由 Docusaurus **3.10.2** 构建。下面克隆示例仓库，只做本地体验。

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

没有 nvm 时，用其他方式安装 `.nvmrc` 指定版本即可。

工具链、类型、构建和审计门禁应退出 `0`。打开 <http://127.0.0.1:3000/pocketrun/>；修改后重新 build，按 `Ctrl+C` 结束预览。

日常写作在 `website/` 运行 `npm start -- --host 127.0.0.1 --port 3000 --no-open`。

## 更新教程

发布练习先 Fork 或使用自己有写入权限的仓库，再按第 0 章的 `OWNER` / `REPO` 流程克隆。修改正文和站点配置后，向自己的仓库提交 PR；合并其 `main` 会部署自己的站点。

完整命令、审计记录与线上验证步骤见[第 0 章](https://kkkhs.github.io/pocketrun/chapter-0/)。设备实践仅使用自己拥有或获授权的设备与演示数据。
