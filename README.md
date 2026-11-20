# PocketRun：从零构建 Android AI 助手

面向 React / TypeScript 开发者，学习让 Agent 在 Android 手机上操作 App、处理文件和执行命令。

教程站已发布至[第 6 章](https://kkkhs.github.io/pocketrun/chapter-6/)：从 React 调用 Kotlin，逐步接通 App 自有 ADB、UID 2000 后端、虚拟屏观察与输入、私有工作目录和命令管道。

第 7—11 章已有代码与教程 Draft PR：最小 Alpine、PTY、手机审批宿主、单任务上下文和模型工具循环。新版真机验收、真实模型调用、合并与网站发布仍待完成；源码可见或本地检查通过不等于完整交付。

## 阅读教程

- [课程导读](https://kkkhs.github.io/pocketrun/)
- [第 1 章：让界面调用 Kotlin](website/docs/chapter-1.md)
- [第 2 章：让 App 自己连接 ADB](website/docs/chapter-2.md)
- [第 3 章：启动 shell 后端](website/docs/chapter-3.md)
- [第 4 章：把 Demo App 放进虚拟屏](website/docs/chapter-4.md)
- [第 5 章：看见界面，再操作它](website/docs/chapter-5.md)
- [第 6 章：工作目录与命令管道](https://kkkhs.github.io/pocketrun/chapter-6/)
- [第 7 章：PRoot 与最小 Alpine 环境（本地开发草稿，正常演示已实测，未全面验收/未发布）](website/docs/chapter-7.md)
- [第 8 章：真正的交互式 PTY（本地草稿，待真机验收）](website/docs/chapter-8.md)
- [第 9 章：工具校验、手机审批与宿主（待真机验收）](website/docs/chapter-9.md)
- [第 10 章：把两次调用放进同一个任务（Draft PR #17）](website/docs/chapter-10.md)
- [第 11 章：让模型选下一步，仍由手机批准（Draft PR #18）](website/docs/chapter-11.md)
- [已发布章节源码快照](https://github.com/kkkhs/pocketrun/releases)
- [参考：系统概览](https://kkkhs.github.io/pocketrun/architecture/)
- [参考：运行与发布教程站](https://kkkhs.github.io/pocketrun/chapter-0/)

Android 示例在 [`app/`](app/README.md)，与下面的教程站使用独立依赖。

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
npm run audit:ci
npm run serve -- --host 127.0.0.1 --port 3000 --no-open
```

没有 nvm 时，用其他方式安装 `.nvmrc` 指定版本即可。

工具链、类型、构建和审计门禁应退出 `0`。打开 <http://127.0.0.1:3000/pocketrun/>；修改后重新 build，按 `Ctrl+C` 结束预览。

日常写作在 `website/` 运行 `npm start -- --host 127.0.0.1 --port 3000 --no-open`。

## 更新教程

发布练习先 Fork 或使用自己有写入权限的仓库，再按第 0 章的 `OWNER` / `REPO` 流程克隆。修改正文和站点配置后，向自己的仓库提交 PR；合并其 `main` 会部署自己的站点。

完整命令、审计记录与线上验证步骤见[第 0 章](https://kkkhs.github.io/pocketrun/chapter-0/)。设备实践仅使用自己拥有或获授权的设备与演示数据。
