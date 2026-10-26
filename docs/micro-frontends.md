# qiankun 3 微前端

## 版本与改造范围

本次按要求使用 **qiankun `3.0.0-rc.22`** 和官方 **`@qiankunjs/bundler-plugin` `0.0.1-rc.2`**，均精确锁定并提交 `pnpm-lock.yaml`。截至本次迁移，npm 的 `latest` 仍是 qiankun 2，3.x 是 `rc` 预发布版本；安装本仓库应使用锁文件，不执行 `pnpm add qiankun@latest`。新官网的 `next` 文档可能比已发布包更新，代码以 rc.22 的实际导出和类型为准，未使用尚未进入该包的 `loadTimeout`、`unload()` 等接口。

已有 `apps/web-ele`（Vue 3、Element Plus、Vben）作为生产主应用，保留原登录、菜单、权限、标签页和业务页面。它通过 `loadMicroApp` 加载 `apps/web-data-center` 的原生 ESM HTML 入口。其他四套管理端仍可独立使用，本次微前端接入集中在默认 `web-ele`。

数据中心子应用查询现有后端的 `/system/dept/simple-list`、`/system/dict-data/simple-list`，提供数量概览、部门目录和字典搜索。它是后续业务拆分的起点；紫金桥客户端、凭据和接口调用留在后端公共模块，浏览器没有直接连接实时数据库。

新增的 `apps/web-micro-demo` 是一个独立演示宿主：复用完全相同的子应用，通过明确标注的演示数据桥接展示生命周期、路由和会话通信，不需要 Java、数据库或登录账号，也不进入生产权限菜单。详见 [可运行集成例子](micro-frontend-example.md)。

| 位置 | 职责 |
| --- | --- |
| `apps/web-ele/src/views/data-center/index.vue` | 生产加载、认证/租户桥接、重试和卸载 |
| `apps/web-ele/src/router/access.ts` | 后端菜单模式补入受登录保护的数据中心入口 |
| `apps/web-ele/src/router/routes/modules/data-center.ts` | 前端/混合菜单模式对应路由 |
| `apps/web-data-center` | 官方 Vite 插件、原生 ESM 生命周期和工作台 |
| `apps/web-micro-demo` | 无后端的轻量独立宿主与可取消演示数据桥接 |
| `packages/micro-apps` | 共享 props 类型、路由白名单、串行生命周期控制 |
| `scripts/micro-demo.mjs` | 同一进程启动/关闭两个 Vite dev 或 preview 服务 |
| `scripts/test-micro-demo.mjs` | 开发与生产均可运行的真实浏览器回归 |
| `config/web-ele.env.example` | 原管理端开发配置样例 |
| `scripts/deploy/nginx.micro.conf` | 同源主子应用及 API/WebSocket 部署示例 |

## 从 qiankun 2 迁移的实际变化

1. 将 `strictStyleIsolation` 改为 v3 的 `sandbox: { styleIsolation: true }`，保留 JavaScript 沙箱；样式隔离现在使用原生 CSS `@scope`，不再创建 Shadow DOM。
2. 删除 UMD library 模式和 build-watch 开发包装，改用标准 `index.html`、`<script type="module">`、普通 Vite 开发服务，以及从 `@qiankunjs/bundler-plugin/vite` 导入的 `qiankun()`。
3. 子应用继续直接导出 `bootstrap`、`mount`、`update`、`unmount`；初始化放在 `mount`，清理放在 `unmount`，不依赖再次执行模块顶层代码。
4. 明确配置 Vite 8 的 HTML 与 `src/main.ts` 双入口、`preserveEntrySignatures: 'allow-extension'` 和 `modulePreload: false`。官方 rc.2 插件负责 CORS 和入口标记，但本身不保留生命周期导出；Vite 默认构建会移除这些导出，`strict` 又可能使 HTML 指向缺少原名称的共享 chunk。当前配置生成一个正确标记的 ESM 入口，导出四个生命周期，生产浏览器已实测。
5. 同时处理 qiankun 暴露的 `loadPromise`、`bootstrapPromise` 和 `mountPromise`；入口 503 时显示错误，重试可恢复，不遗留未处理的 Promise 拒绝。
6. 保留 props 函数桥接，不使用 v3 已移除的 `initGlobalState`。共享 `DataCenterProps` 使用对象类型别名以满足 v3 的 `Record<string, unknown>` 约束，没有放宽为 `any`。

## 运行生产管理端

前置要求沿用仓库：Node.js `^22.18.0` 或 `^24.12.0`，pnpm 11。下面命令均在 `yudao-ui-admin-vben-master` 目录运行。

```bash
pnpm install --frozen-lockfile
cp config/web-ele.env.example apps/web-ele/.env.local
```

分别运行两个终端：

```bash
pnpm dev:data-center
```

```bash
pnpm dev:ele
```

访问主应用 `http://localhost:5173/`，用实际后端账号登录后打开“数据中心”。子应用开发入口是 `http://127.0.0.1:5175/`（`localhost:5175` 也适用）。主应用 Vite 仍默认将 `/admin-api` 与 `/infra/ws` 代理到 `localhost:48080`；实际地址在 `apps/web-ele/vite.config.ts` 配置。租户和验证码开关须与后端一致。

子应用可单独打开：开发为 `http://127.0.0.1:5175/`；构建后 `pnpm -F @vben/web-data-center preview` 的地址是 `http://127.0.0.1:5175/micro-apps/data-center/`。独立预览使用 hash 路由，显示未连接状态，没有演示数据或业务令牌。要看无需后端的完整演示，应运行 `pnpm dev:micro-demo`。

## 路由、权限与清理

主应用路径 `/data-center` 对应 `?view=overview`、`?view=departments`、`?view=dictionaries`。嵌入时子应用使用 memory history，宿主拥有浏览器 URL；宿主路由变化通过 `update` 下发，子应用导航通过 `onNavigate` 回传。支持刷新、前进/后退，并对白名单外路径归一为总览。

旧数据库无需补菜单 SQL：后端菜单模式会补入不存在的 `/data-center`；已有同路径菜单保留后端定义。该路由仍经过原登录守卫，不属于免登录路由。两个精简列表接口的实际权限、租户隔离继续由后端控制。

生产宿主只传递显示名称、有效租户、语言、会话标识和两个只读函数。访问/刷新令牌和紫金桥凭据不经 props 分发。请求由原 `requestClient` 发出，保留续期、认证头、租户头、访问租户头及错误处理。

切换用户/租户时宿主撤销旧请求，再下发新会话与 bridge；子应用清空旧数据重新加载。退出登录或页面销毁会取消请求、串行等待进行中的挂载并卸载。Vue 实例、路由监听和 history 在 `unmount` 中清理；旧请求即使迟到也不能回填。重复重试使用每次操作独有的版本号，避免等待卸载期间产生重复实例。可能被取消的导航守卫不会提前卸载仍可见的子应用。

`@scope` 将子应用样式限制到应用容器，但不像 Shadow DOM 那样阻止宿主样式向内影响。因此子应用仍使用 `.data-center-app` 前缀，示例宿主的控制区样式也限定在自身类名下。后续弹窗、浮层等组件需要将容器设在子应用内部。qiankun 的同页面沙箱不是运行不可信代码的安全边界，入口必须来自受控部署。

## 构建与部署

```bash
# 原管理端与真实子应用
pnpm build:micro

# 无后端示例宿主与同一个真实子应用
pnpm build:micro-demo
pnpm preview:micro-demo

# 10 项回归 + 子应用/示例完整类型检查 + 生产宿主改动的真实依赖类型检查
pnpm check:micro
```

生产子应用 `base` 固定为 `/micro-apps/data-center/`，输出 `dist/index.html` 和带 hash 的 `dist/assets/*.js`、`*.css`。HTML 中只有一个 `type="module" entry`，入口直接导出生命周期；没有 UMD 全局对象。主子各自打包 Vue，不要求框架运行时共享。

生产主应用将 `VITE_GLOB_DATA_CENTER_ENTRY` 配置为 `/micro-apps/data-center/`；未设置也会使用此默认值。该变量进入原主应用运行时配置，可按原项目方式部署后调整。如果改成跨域入口，目标服务仍应在 `/micro-apps/data-center/` 提供资源，或同步修改子应用 `base` 后重新构建。

部署结构：

```text
/usr/share/nginx/html/
  index.html                       # web-ele/dist 全部内容
  assets/...
  micro-apps/data-center/
    index.html                     # web-data-center/dist 全部内容
    assets/main-<hash>.js
    assets/main-<hash>.css
```

使用 `scripts/deploy/nginx.micro.conf`，将 `yudao-server:48080` 替换为实际后端。子应用资源缺失返回 404，不回退为宿主 HTML；ESM 文件必须返回正确 JavaScript MIME。示例保留 `/admin-api`、`/infra/ws` 代理及 WebSocket Upgrade，并清空外部传入的内部身份头。更改后端 WebSocket 路径时同步修改对应 location。

官方插件为开发/预览开启宽松 CORS；两个 Vite 服务限定监听 `127.0.0.1`，这些服务不作为生产服务。生产推荐同源。跨域时为入口 HTML、模块和 CSS 配置明确的允许来源，所有资源都要可被宿主读取。若有 CSP，请按官方 [CSP 文档](https://www.qiankunjs.com/guide/csp-requirements) 配置原生 ESM 沙箱使用的 blob 脚本/样式和相关加载源；不要把开发服务的策略直接搬到公网。

## 浏览器与预发布限制

根据 [qiankun 官方浏览器要求](https://www.qiankunjs.com/guide/browser-support)，本项目同时启用原生 ESM 与 `@scope`，可使用 Chrome/Edge 133+ 或 Safari 18.4+；Firefox 默认不具备所需的动态多 import map 能力。基础 `isRuntimeCompatible()` 并不能代表 ESM/@scope 全部能力，项目不绕过 qiankun 的浏览器检测，也不提供旧浏览器兼容回退。

3.x 仍为 RC。升级前应核对实际发布包 API、锁文件、真实开发与生产浏览器回归，以及目标环境 CSP/CORS；不能仅以编译成功认定迁移完成。

## 本次验证

- 官方 rc.22/rc.2 实际包类型与源码已核对；`pnpm check:micro` 的 10 项测试、子应用完整类型检查、示例完整类型检查和 `web-ele/tsconfig.micro.json` 的真实依赖检查均通过。
- `build:micro-demo` 生成原生 ESM HTML、JS 和 CSS；Chromium 134 在开发与生产分别通过 `test:micro-demo`，覆盖 @scope、双向路由、返回/刷新、租户与用户更新、故障恢复、请求取消、卸载/再次挂载、退出/登录和快速重试，均为 0 页面脚本错误。
- `pnpm -F @vben/web-ele build` 生产构建通过；连接真实 PostgreSQL 后端在 Chromium 134 登录，验证 12 条实际部门、1556 条实际字典、ESM/@scope、返回/刷新、3 次快速离开与重新挂载、认证与正确租户头、通知查询业务码 0、退出销毁，全程无页面脚本错误。
- 补充验证子应用独立预览、首次入口 503 后重试恢复；一键启动器在 SIGTERM 后释放两个端口，第二服务端口占用时关闭第一服务并以非零状态退出。
- 原管理端全量 `vue-tsc` 在此前执行环境中因内存限制被终止；定向检查不代表所有既有业务页面已通过完整类型验收。保留 `pnpm -F @vben/web-ele typecheck` 供资源充足的 CI 单独运行。
- 原始种子中部分 CMS 菜单没有对应前端视图，原页面也含外部图标/图片；本次没有扩展为补建所有 CMS 页面或替换外链资源。

## 官方参考

- [qiankun 3 仓库与发布状态](https://github.com/umijs/qiankun)
- [v2 → v3 迁移](https://www.qiankunjs.com/cookbook/migrate-from-2x)
- [Vite 微应用](https://www.qiankunjs.com/cookbook/prepare-a-vite-app)
- [AppConfiguration 与 CSS @scope](https://www.qiankunjs.com/api/configuration)
- [Vite 构建配置](https://vite.dev/config/build-options)
