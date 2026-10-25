# qiankun 微前端

## 改造范围

默认使用已有的 `apps/web-ele`（Vue 3、Element Plus、Vben）作为主应用，保留登录、菜单、权限、标签页及全部原业务页面。增加 `apps/web-data-center` 独立 Vue 子应用，主应用通过 qiankun `loadMicroApp` 加载其 HTML 入口。

子应用已经实现可用的部门/字典工作台：从现有后端 `/system/dept/simple-list`、`/system/dict-data/simple-list` 查询真实数据，展示数量、搜索部门和字典。它是后续数据中心业务拆分的起点，不是紫金桥实时库可视化界面。紫金桥凭据和调用逻辑留在后端公共模块，浏览器不直接连接实时库。

其他四套管理端 `web-antd`、`web-antdv-next`、`web-naive`、`web-tdesign` 仍可独立使用；本次将默认 `web-ele` 接入 qiankun，未将同一套管理功能重复拆成四个子应用。

| 位置 | 职责 |
| --- | --- |
| `apps/web-ele/src/views/data-center/index.vue` | 主应用加载、会话桥接、错误重试、卸载 |
| `apps/web-ele/src/router/access.ts` | 在后端菜单模式补入受登录保护的数据中心菜单 |
| `apps/web-ele/src/router/routes/modules/data-center.ts` | 前端/混合菜单模式的对应路由 |
| `apps/web-data-center` | 子应用、独立 UMD 构建、生命周期及工作台 |
| `packages/micro-apps` | 通信类型、路由白名单、串行生命周期控制 |
| `config/web-ele.env.example` | 主应用开发配置样例 |
| `scripts/deploy/nginx.micro.conf` | 同源主子应用部署示例 |

## 本地运行

前置要求沿用仓库配置：Node.js `^22.18.0` 或 `^24.12.0`，pnpm 11。以下命令均在 `yudao-ui-admin-vben-master` 目录运行。

```bash
pnpm install --frozen-lockfile
cp config/web-ele.env.example apps/web-ele/.env.local
```

终端一运行子应用：

```bash
pnpm dev:data-center
```

终端二运行主应用：

```bash
pnpm dev:ele
```

访问 `http://localhost:5173`，使用实际后端账号登录，然后打开“数据中心”。后端默认由主应用 Vite 代理至 `http://localhost:48080/admin-api`；修改目标地址时调整 `apps/web-ele/vite.config.ts`。示例开启租户和验证码，需与后端设置保持一致。

子应用在 `http://localhost:5175/` 也可以独立打开，使用自己的 hash 路由。独立预览不会伪造业务数据，也不读取主应用的本地令牌；真实数据由主应用注入接口函数后加载。

### 为什么开发时使用构建监听

本次锁定 qiankun `2.10.16`。qiankun 2 的 HTML 入口加载器需要经典脚本导出的生命周期，而 Vite 开发服务默认生成原生 ES module。子应用使用 Vite library 模式输出一个包含 Vue/Router 的 UMD 入口，HTML 中以 `entry` 脚本加载。开发命令先构建，再开启 `vite build --watch` 和 `vite preview`，和生产使用相同的入口契约。改动子应用后等待构建完成，再刷新主应用；不依赖未经验证的 ESM 沙箱绕过插件。

主应用仍使用原 Vite 开发服务和热更新。新增子应用可以选择其他技术栈，只要导出 qiankun 生命周期并遵循通信契约。

## 构建与验证

```bash
# 构建主应用与子应用
pnpm build:micro

# 单独构建子应用
pnpm build:data-center

# 生命周期、路由、旧会话响应隔离测试，以及主子应用改动的类型检查
pnpm check:micro

# 原管理端完整类型检查（保留原有检查范围）
pnpm -F @vben/web-ele typecheck
```

子应用输出 `apps/web-data-center/dist/index.html`、`data-center.umd.js`、`style.css`；主应用输出仍为 `apps/web-ele/dist`。UMD 包不外置 Vue，因此主子应用不要求共享框架版本或加载顺序。

## 路由、权限和会话

主应用路径为 `/data-center`，子页面映射到 `?view=overview`、`?view=departments`、`?view=dictionaries`。子应用在嵌入时使用 memory history；因此既有主应用的 hash/history 模式均不被子应用夺取。页面刷新、浏览器前进后退会通过 `update` 同步子页面。子应用自己的导航仅接受约定的三个路由。

“数据中心”入口在默认后端菜单模式下自动补入，所以旧数据库无需补菜单 SQL 即可使用；若后端已存在 `/data-center` 则保留后端定义。此路由不在免登录的 `coreRoutes` 中，仍经过现有登录守卫。当前子应用只调用原来供已登录用户使用的精简列表接口，最终权限和租户过滤由后端负责。

主应用通过 `DataCenterProps` 传递用户名、有效租户、语言、会话标识和两个明确的只读函数。访问令牌、刷新令牌、紫金桥 token 不通过 props 传播。请求复用原有 `requestClient`，保持自动续期、租户头、访问租户头和错误处理。

切换用户或租户时，主应用中止旧请求并更新 props，子应用立即清空旧数据重新加载。令牌过期/退出登录时会中止请求并卸载子应用；重新登录后可以重新挂载。切页导致组件销毁时，生命周期控制器串行清理尚在挂载或已挂载的实例；不在可能被取消的导航守卫中提前销毁应用。子应用销毁 Vue 实例、路由监听和未完成请求；旧请求即使迟到，也不能写入新会话。

主应用采用 qiankun JavaScript 沙箱和 Shadow DOM 样式隔离。子应用不依赖向 `document.body` 传送弹窗的第三方 UI 库。以后引入此类组件时，应将弹窗容器显式设为当前子应用容器。

## 部署

生产构建前，将主应用配置中的 `VITE_GLOB_DATA_CENTER_ENTRY` 改为 `/micro-apps/data-center/`。该变量也写入现有的主应用运行时配置，可按原项目方式部署后调整。未设置时，生产默认使用同源路径 `/micro-apps/data-center/`。

按以下结构放置两个应用的构建产物：

```text
/usr/share/nginx/html/
  index.html                     # web-ele/dist 的全部文件
  ...
  micro-apps/data-center/
    index.html                   # web-data-center/dist 的全部文件
    data-center.umd.js
    style.css
```

使用 `scripts/deploy/nginx.micro.conf`，并将其中 `yudao-server:48080` 改成实际后端地址。子应用资源路径使用 `try_files ... =404`，避免丢失资源时错误返回主应用 HTML。HTML 与当前固定文件名的子应用脚本/样式使用重新验证缓存策略，避免新入口配旧代码。

示例同时保留原平台 `/infra/ws` 的 WebSocket Upgrade 代理；如果部署配置更改了 `yudao.websocket.path`，同步修改这个 location。若采用网关或另行部署文件服务，继续合并对应环境原有的网关、上传大小、超时和 HTTPS 配置。

跨域部署时，入口 HTML、JS 和 CSS 均需允许主应用的明确 Origin 进行 CORS 读取。开发预览只允许本机 localhost/127.0.0.1 源；生产推荐同源反向代理，不需要开放跨域授权。配置的子应用入口必须是受信任、由本项目控制的内容；qiankun 沙箱不应当作加载不可信代码的安全边界。

## 添加后续业务子应用

1. 在 `apps/` 添加独立应用并输出 qiankun `bootstrap`、`mount`、`unmount`，需要上下文/路由同步时实现 `update`。
2. 为应用使用唯一名称、唯一容器和独立部署目录；生命周期中只在传入容器下查找挂载节点。
3. 通过 `packages/micro-apps` 扩展明确的业务接口契约，保持认证请求由宿主或后端统一负责。
4. 添加对应主应用路由与菜单，明确后端权限；不要把需要登录的页面加到 `coreRoutes`。
5. 覆盖切页、加载失败、再次挂载、退出登录、切换租户和旧请求回填的测试。

## 本次验证记录

- `pnpm build:ele --concurrency=1` 完成 11 个工作区构建任务，主应用生产构建通过。
- `pnpm build:data-center` 生成实际 HTML、UMD 与 CSS 产物；未以 Vite ESM 入口冒充 qiankun 2 子应用。
- `pnpm check:micro` 的 7 个生命周期/路由/会话隔离测试通过；子应用完整类型检查、宿主新增页面和接入路由的真实依赖类型检查均通过。宿主定向检查配置为 `apps/web-ele/tsconfig.micro.json`，沿用原编译选项及环境声明，没有替换业务依赖或关闭类型检查。
- 使用 Chromium 134、真实管理端生产构建和实际 PostgreSQL 后端登录，验证了 UMD 挂载、Shadow DOM、认证/租户请求头、真实部门和字典数据、子页面切换、浏览器返回、刷新以及离开后重新挂载。该过程修复了导航被取消时提前卸载子应用的竞态。
- 最终联调连续执行 3 次快速离开与重新挂载，始终只有一个子应用实例；4 次部门请求均携带认证与正确租户头，4 次通知未读数查询均返回业务码 0。实际退出登录后子应用销毁，最后一轮全程没有页面脚本错误。
- 原管理端全量 `vue-tsc` 在当前执行环境因内存限制被终止，不能将定向检查通过解读为所有原有业务页面已完成类型验收。大型工作区建议单独运行全量检查，避免与并行构建同时争用内存。
- 原始初始化数据中有部分 CMS 菜单，其对应视图不在当前前端目录；原页面也引用外部图标/图片，测试环境出现了外链证书或可用性错误。这些是原项目资源和菜单的限制，本次未扩展到补建全部 CMS 业务。

## 参考

- [qiankun 2 主子应用接入](https://qiankun.umijs.org/guide/getting-started/)
- [qiankun loadMicroApp、update 和样式隔离](https://qiankun.umijs.org/api/)
- [qiankun FAQ：入口生命周期、资源与路由](https://qiankun.umijs.org/faq/)

这些设计使用稳定的 qiankun 2 接口，不依赖 qiankun 3 RC 的原生 ESM 支持。
