# PocketRun：从零构建 Android AI 助手

面向 React / TypeScript 开发者，学习让 Agent 在 Android 手机上操作 App、处理文件和执行命令。

教程站已上线至第 8 章：第 1 章跑通 React → Kotlin，第 2 章取得 App 自有 ADB 启动入口，第 3 章完成 UID 2000 的 `app_process` 后端与 Binder 通信，第 4 章创建演示 VirtualDisplay 并验证 `displayId` 路由，第 5 章从同一显示读取截图和 UI 树、定向输入并重新观察结果。第 6 章在 App 私有目录准备 CSV、运行固定命令管道并检查输出与退出码；云真机专项与真实界面的正常演示分别记录，不代表所有异常分支都已在界面命中。[PR #11](https://github.com/kkkhs/pocketrun/pull/11) 已合并，`chapter-6` 源码快照已发布。后续能力按章节推进。

第 7 章已在任务专用本机模拟器完成最小 Alpine 的安装、映射与重复演示、12 项生命周期、7 个 App/tracer/guest/安装死亡恢复场景。仅含第 7 章能力的实际 App 界面也已通过；[PR #12](https://github.com/kkkhs/pocketrun/pull/12)、main CI、Pages 与 [chapter-7 源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-7)均已完成。第 8 章的独立 PTY、120 秒资源验收、main CI/Pages 与 [chapter-8 源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-8)也已完成。第 9 章的独立 App 工具、审批和故障边界已验收，正式发布推进中。

## 阅读教程

- [课程导读](https://kkkhs.github.io/pocketrun/)
- [第 1 章：让界面调用 Kotlin](website/docs/chapter-1.md)
- [第 2 章：让 App 自己连接 ADB](website/docs/chapter-2.md)
- [第 3 章：启动 shell 后端](website/docs/chapter-3.md)
- [第 4 章：把 Demo App 放进虚拟屏](website/docs/chapter-4.md)
- [第 5 章：看见界面，再操作它](website/docs/chapter-5.md)
- [第 6 章：工作目录与命令管道（已发布）](https://kkkhs.github.io/pocketrun/chapter-6/) · [源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-6)
- [第 7 章：PRoot 与最小 Alpine 环境](https://kkkhs.github.io/pocketrun/chapter-7/) · [源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-7)
- [第 8 章：真正的交互式 PTY](https://kkkhs.github.io/pocketrun/chapter-8/) · [源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-8)
- [第 9 章：工具校验、手机审批与宿主（验收通过，发布中）](website/docs/chapter-9.md)
- [章节源码快照](https://github.com/kkkhs/pocketrun/releases)
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
