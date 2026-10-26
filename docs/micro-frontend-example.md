# 可运行的 qiankun 3 集成例子

这个例子由轻量 Vue 宿主 `apps/web-micro-demo` 和正式子应用 `apps/web-data-center` 组成。它不依赖 Java、PostgreSQL、金仓或登录服务；宿主提供可取消的演示数据函数，子应用仍使用正式的生命周期、路由和视图代码。页面明确显示“演示数据”，没有实时数据库连接，不生成或存储业务登录令牌。

版本固定为 qiankun `3.0.0-rc.22` 与官方 Vite 插件 `0.0.1-rc.2`，属于预发布版本。请使用 Chrome/Edge 133+ 或 Safari 18.4+；本次实测 Chromium 134。

## 1. 一键开发运行

在 `yudao-ui-admin-vben-master` 目录执行：

```bash
pnpm install --frozen-lockfile
pnpm dev:micro-demo
```

打开 **http://127.0.0.1:5174/**。启动器在同一个 Node 进程中启动两个服务：

| 应用              | 开发地址                 | 作用                          |
| ----------------- | ------------------------ | ----------------------------- |
| `web-micro-demo`  | `http://127.0.0.1:5174/` | 宿主控制面板与通信记录        |
| `web-data-center` | `http://127.0.0.1:5175/` | qiankun 加载的真实 Vue 子应用 |

`Ctrl-C` 会关闭两者；任何服务启动失败都会关闭已启动的服务并退出。端口使用 `strictPort`，占用时明确报错，不会悄悄切换到另一个端口。不需要额外安装进程管理工具。

首次加载会自动挂载子应用。子应用直接在浏览器打开时显示未连接状态；只有从示例宿主加载，才收到演示 bridge。

## 2. 建议操作顺序

| 操作                       | 应看到的行为                                                             |
| -------------------------- | ------------------------------------------------------------------------ |
| 打开页面                   | 状态“已挂载”；子应用“演示数据”；2 个部门、2 种字典、3 条字典数据         |
| 点击“宿主打开部门”         | 宿主 URL 变为 `#/data-center?view=departments`，通过 `update` 同步子页面 |
| 点击子应用“数据字典”       | 子应用通过 `onNavigate` 回传，宿主 URL 和记录同步变化                    |
| 浏览器后退或刷新           | URL 所表示的子页面恢复；嵌入子应用使用 memory history                    |
| 切换“演示租户”为乙         | 旧请求取消，部门/字典替换为“演示乙”，没有旧租户内容回填                  |
| 切换演示用户               | 子应用会话名称同步，重新创建会话 bridge                                  |
| 勾选“模拟数据请求失败”     | 子应用出现可读错误；关闭后 `update` 自动重新加载，可再次使用“刷新数据”   |
| 加载数据时点击“卸载子应用” | 子应用 DOM 消失，进行中的请求计数归零，日志出现 unmount 完成             |
| 点击“挂载 / 重试”          | 同一容器重新挂载，始终只有一个子应用实例                                 |
| 点击“退出演示会话”         | 撤销 bridge、取消请求、卸载子应用；再次登录可重新挂载                    |
| 快速重复点击“挂载 / 重试”  | 旧操作被版本号淘汰，挂载/卸载串行处理，不出现重复实例                    |

页面底部保留最近 20 条生命周期和通信记录。这是可读的集成演示，不会收集账号凭据或发送日志到外部服务。

## 3. 验证生产产物

先停止开发服务，然后执行：

```bash
pnpm build:micro-demo
pnpm preview:micro-demo
```

宿主仍为 **http://127.0.0.1:5174/**。此时实际加载的是 **http://127.0.0.1:5175/micro-apps/data-center/** 下的构建产物，包含原生 `type="module" entry` 与带 hash 的 JS/CSS，而不是开发源文件。

构建目录分别是 `apps/web-micro-demo/dist` 和 `apps/web-data-center/dist`。演示宿主默认生产入口为上述本机地址，适用于本地生产验收。要把演示宿主部署到自己的站点，可在构建时设置 `VITE_DATA_CENTER_ENTRY=/micro-apps/data-center/`，按 [生产部署说明](micro-frontends.md#构建与部署) 将子应用放到同域对应路径。演示宿主不使用正式 `web-ele` 的运行时配置文件，也不会成为生产后台菜单的一部分。

## 4. 自动化回归

```bash
# 生命周期、路由、旧会话响应和演示请求取消；三个应用的相关类型检查
pnpm check:micro

# 首次运行浏览器测试时安装 Playwright Chromium
pnpm exec playwright install chromium
```

在 `dev:micro-demo` 或 `preview:micro-demo` 已运行时，另开终端：

```bash
pnpm test:micro-demo
```

测试默认访问 `http://127.0.0.1:5174/`；需要其他地址可通过 `MICRO_DEMO_URL` 设置。若环境已有受支持的 Chromium，可设置 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 指向其可执行文件。测试检查实际 DOM、CSS `@scope`、双向导航、返回/刷新、租户/用户变化、故障恢复、卸载时请求归零、再次挂载与退出，并补充独立预览及首次入口 503 后重试恢复，要求没有页面脚本错误。开发和生产应各运行一次。

本次这两个模式均已在 Chromium 134 通过；10 项单元回归和真实依赖类型检查也已通过。浏览器测试使用同一真实子应用，没有替换 qiankun 或生命周期。

### CI 验证与真实页面截图

仓库的 `New data center verification` 工作流会在面向 `main` 的 PR 和 `main` 更新时执行。前端任务冻结安装依赖，执行 `check:micro` 和 `build:micro-demo`，随后启动构建产物的 preview，用 Playwright Chromium 跑完整浏览器回归；runner 安装 Noto CJK 字体以显示中文。

要在本机运行回归的同时输出实际页面截图，可设置可选目录：

```bash
# 先在另一终端启动 pnpm preview:micro-demo
MICRO_DEMO_SCREENSHOT_DIR=/tmp/micro-demo-artifacts pnpm test:micro-demo
```

截图包含桌面（1440 × 1100）和移动端（390 × 844）两个视口的完整页面。截图前要求真实子应用已挂载、请求完成、演示数据统计为 2 / 2 / 3、无页面错误及横向溢出。`verification.json` 记录浏览器版本、视口、入口、时间和 CI 检出的提交；未指定目录时保留原有回归行为，不写截图。截图显示的是正式子应用通过示例宿主集成后的效果，**数据仍为明确标注的演示数据，不是业务数据库截图**。

工作流产物 `micro-demo-preview-<sha>` 保存两张 PNG、验证记录和 preview / 浏览器测试日志，保留 7 天。独立 Java 17 任务连接临时 PostgreSQL 服务运行 `mvn -pl yudao-server -am test`，汇总 Surefire 的测试、失败、错误及跳过数量，并要求两项外部数据库回归确实执行、不得跳过；报告在 `java-postgresql-tests-<sha>` 中。该任务不代表 KingbaseES 服务实测通过。

工作流仅使用 `contents: read`，不读取仓库 secrets、不保留 Git 凭据、不部署。浏览器和 PostgreSQL 均由临时 GitHub-hosted runner 提供；依赖和官方 actions 固定版本或提交。

## 5. 关键代码

- `apps/web-micro-demo/src/app.vue`：调用 `loadMicroApp`，通过共享控制器串行挂载、更新、卸载；维护会话和路由桥接。
- `apps/web-micro-demo/src/demo-bridge.ts`：按租户生成明确标注的示例部门/字典，使用 `AbortSignal` 取消定时请求并释放监听器。
- `apps/web-data-center/src/lifecycle.ts`：实际 `bootstrap/mount/update/unmount`、容器内挂载、memory/hash 路由和清理。
- `apps/web-data-center/src/views/workbench.vue`：同一真实工作台；通过 context 标记区分演示、正式连接和独立预览。
- `apps/web-data-center/vite.config.ts`：官方 qiankun 插件与显式保留 ESM 导出的 Vite 8 配置。
- `packages/micro-apps/src/types.ts`：业务 props 和两个只读 bridge 方法；生产宿主使用原请求客户端，示例宿主使用本地演示函数。

把示例接到自己的后端时，替换 bridge 的具体实现并保留取消与会话校验逻辑。不要把示例数据桥接复制进生产鉴权逻辑，也不要把后端数据库或实时库凭据放进浏览器。
