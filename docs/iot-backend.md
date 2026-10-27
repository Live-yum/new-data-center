# IoT 设备看板后端

## 边界与架构

新增 `yudao-module-iot`：普通 jar 供 `yudao-server` 聚合；`yudao-module-iot-exec.jar` 是独立 Spring Boot 微服务，**不是仅给单体增加接口**。关系库存设备元数据、统一指标→紫金桥原始点名映射、版本化看板/组件/模板布局、私有导出队列与结果。实时和历史读取复用原 `RealDbClient`，部署命名注册表为每个数据源维护独立客户端；公共 starter 的 66 操作契约未改动。IoT 对外**没有**SQL、URL、设备写入、控制、凭据或用户管理透传接口。

本服务没有演示数据回退。未启用 RealDB 明确报错；前端独立演示 bridge 必须标注演示，不可作为生产结果。设备列表是平台已配置设备，不是供应商所有设备；管理员配置映射后才能查询。首次数据库为空，模板亦为空；通过模板接口保存可复用布局，不自动塞入假设备/点名。

## 启动方式

Java 17 / Maven 3.9+。先在**已备份、受控的测试库**人工审阅并应用增量脚本：

```
yudao-cloud-mini-master-jdk17/sql/postgresql/upgrade/20261026_iot.sql
yudao-cloud-mini-master-jdk17/sql/postgresql/upgrade/20261027_iot_process.sql
yudao-cloud-mini-master-jdk17/sql/postgresql/upgrade/20261027_iot_sources.sql
```

基础/工艺脚本使用 `CREATE ... IF NOT EXISTS`；多源脚本只增补 IoT 表列/索引，并将没有解析快照的旧 QUEUED/RUNNING 导出明确标 FAILED。不会重查旧任务、删除旧表、修改其他业务表或分配账号角色权限；启动不执行迁移。`IF NOT EXISTS` 不是结构升级检测，已有同名但不同结构的表必须先人工比对。支持 PostgreSQL 与 PostgreSQL 兼容模式金仓；金仓真实实例未在本变更中验证。

```bash
cd yudao-cloud-mini-master-jdk17
mvn -pl yudao-module-iot -am verify
# 必须设置实际测试库密码；不要将凭据写入版本库
export DB_PASSWORD='your-test-database-password'
java -jar yudao-module-iot/target/yudao-module-iot-exec.jar
# 聚合模式
mvn -pl yudao-server -am package
java -jar yudao-server/target/yudao-server.jar
```

独立主类自动启用 `iot` profile，其配置位于 `application-iot.yaml`。默认端口 48083 (`IOT_SERVER_PORT`)；数据库 `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD/DB_JDBC_URL` 沿用公共配置，Redis `REDIS_HOST/REDIS_PORT/SPRING_DATA_REDIS_PASSWORD`。可用外部配置覆盖 `spring.config.import` 接入金仓，勿把聚合配置整个复制进独立服务。

独立服务默认使用 Spring Cloud simple discovery：`system-server` → `SYSTEM_SERVICE_URL` (默认 `http://127.0.0.1:48081`)，`infra-server` → `INFRA_SERVICE_URL` (默认 `http://127.0.0.1:48082`)。这是运维控制的上游配置，不是浏览器可传入 URL。真实登录/权限/租户校验依赖现有独立 system 服务，日志依赖 infra；须按现有微服务部署方式保护内部 RPC 网络。**聚合服务拒绝 HTTP RPC，不能把它当成上述远端 RPC 替代品。** 独立进程可在上游未运行时启动，但带 token 的业务操作需要上游可用；401 smoke 不是完整业务联调。

服务不信任外部 `login-user`，`mock-enable=false`。网关必须把 `/admin-api/iot/**` 路由到该服务并保留 `Authorization` 与经现有租户校验的 `tenant-id`，清除外部 `login-user`。聚合模式沿用原安全配置。本变更不修改生产网关或部署，不放开 health 匿名权限。RealDB 启用/连接凭据按 [原文档](realdb-api.md) 外部配置；独立 `REALDB_ENABLED` 默认 false。`yudao.iot.realdb-zone` (`REALDB_ZONE`，默认 UTC) 必须与供应商服务器时区一致。

## 身份、权限与隔离

所有端点经过现有 token 身份认证、租户过滤器和方法权限。`tenant_id` 来自已验证上下文，不取请求 JSON；JdbcTemplate 不走 MyBatis 租户拦截，因此每个业务 SQL 显式约束 tenant。每次解析设备/映射均核对租户，查询只接受平台设备ID与统一指标，不接受原始点名。看板固定/多设备绑定保存时也核验租户。

权限键供管理员在**既有权限管理流程**中按需配置，本变更不会自动授予任何角色：

| 权限 | 操作 |
|---|---|
| `iot:device:query` | 设备、映射读取 |
| `iot:device:configure` | 创建设备、配置映射 |
| `iot:dashboard:query` | 看板、模板读取 |
| `iot:dashboard:configure` | 看板/模板新增、修改、删除 |
| `iot:data:query` | 实时、历史数据 |
| `iot:data:export` | 创建、查看、取消自己的导出及下载 |

当前授权粒度是**租户+功能权限**（有 data:query 的用户可读取该租户已配置的设备），不声称提供逐设备ACL。映射配置者可选择服务账号能读取的任意点名，需由可信租户管理员维护；每个命名源须由运维配置明确的租户 allowlist 和独立上游只读账号。新命名源默认不向任何租户开放；同一数据源允许多个租户意味着这些租户管理员可选择该账号能读的测点，源 allowlist 不是点级 ACL。下载另外约束 `owner_id=登录用户`，即同租户其他用户也不能凭任务ID下载。

## HTTP 契约

前缀 `/admin-api/iot`；除文件外均为现有 `CommonResult {code,data,msg}` 信封。业务错误遵循现有框架，不保证HTTP状态与业务code一致。JSON数字设备/看板ID；导出ID为UUID字符串。

| 方法/路径 | 请求 | 成功data |
|---|---|---|
| GET `/devices` | 无 | `Device[]`，最多500 |
| GET `/sources` | 无 | 当前租户允许使用的部署数据源安全状态 `Source[]` |
| GET `/process-pages` | 无 | 当前租户完整 `ProcessScene[]`，最多100 |
| GET `/process-pages/{id}` | 无 | 完整 ProcessScene |
| POST `/process-pages` | ProcessScene，version=0、id省略或0 | 完整 ProcessScene |
| PUT `/process-pages/{id}` | ProcessScene，携带当前version | 完整 ProcessScene，version+1 |
| DELETE `/process-pages/{id}?version=N` | 当前version | boolean |
| POST `/devices` | `{name,code,sourceId}` | 完整Device |
| GET `/devices/{id}/mappings` | 无 | `Mapping[]` |
| PUT `/devices/{id}/mappings` | `Mapping[]`，整体替换 | 完整Mapping[] |
| GET `/dashboards` | 无 | 完整Dashboard[]，最多100 |
| GET `/dashboards/{id}` | 无 | 完整Dashboard |
| POST `/dashboards` | Dashboard，version=0 | 完整Dashboard |
| PUT `/dashboards/{id}` | Dashboard，携带当前version | 完整Dashboard，version+1 |
| DELETE `/dashboards/{id}?version=N` | 当前version | boolean |
| GET `/templates` | 无 | 完整Dashboard[]，当前租户 |
| POST `/templates` | Dashboard，所有binding=currentDevice | 完整Dashboard |
| POST `/query/realtime` | Query，aggregation=RAW | QueryResult |
| POST `/query/history` | Query | QueryResult |
| GET `/exports` | 无 | 自己最近100条ExportJob |
| POST `/exports` | 与history相同Query | ExportJob(QUEUED) |
| GET `/exports/{id}` | 无 | ExportJob |
| POST `/exports/{id}/cancel` | 无 | ExportJob |
| GET `/exports/{id}/file` | 已鉴权请求 | `text/csv;charset=UTF-8`附件字节 |

```ts
type Device = { id: number; name: string; code: string; sourceId: string };
type Mapping = { metricKey: string; name: string; pointName: string; unit: string };
type Dashboard = { id?: number; name: string; version: number; schemaVersion: 1; widgets: Widget[] };
type Widget = {
  id: string; title: string; type: 'line'|'gauge'|'stat'|'bar'|'table'|'timeline'|'image';
  binding: { mode: 'currentDevice'|'fixed'|'multiple'; deviceIds: number[]; metricKey: string };
  grid: { x: number; y: number; w: number; h: number }; // 12列；0<=x,x+w<=12
  unit?: string; timeWindowSeconds: number; aggregation: 'RAW'|'AVG'|'MIN'|'MAX'|'LAST';
  min?: number; max?: number; imageUrl?: string;
  hotspots?: {x:number; y:number; label:string; metricKey?:string}[]; // x/y 0..100百分比
};
type Query = {
  deviceIds: number[]; metricKeys: string[]; aggregation: 'RAW'|'AVG'|'MIN'|'MAX'|'LAST';
  startTime?: string; endTime?: string; // ISO8601，必须包含Z或offset
  intervalMs?: number;
};
type SourceError = { sourceId:string; code:'SOURCE_UNAVAILABLE'|'SOURCE_FORBIDDEN'|'SOURCE_TIMEOUT'|'SOURCE_BUSY'|'SOURCE_QUERY_FAILED'|'SOURCE_CHANGED'; message:string };
type QueryResult = { source:'REALDB'; partial:boolean; errors:SourceError[]; series: {
  sourceId:string; deviceId:number; metricKey:string; unit:string;
  points:{ time:string; value:number|string|null; quality:'GOOD'|'BAD'|'UNCERTAIN'|'UNKNOWN'|'MISSING'; timeSource:'DEVICE'|'RECEIVED' }[];
}[] };
type ExportJob = {id:string; status:'QUEUED'|'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELLED'|'EXPIRED';
  createdAt:string; expiresAt:string; error:string|null; sourceIds:string[]};
```

`currentDevice` 保存空deviceIds，运行时前端把当前选择展开进Query；fixed恰好1台；multiple 1–10台；image只允许currentDevice/fixed，避免丢弃其他设备系列。模板只允许currentDevice。看板最多50组件、布局高度/时间窗/单位/热点数量均受限，imageUrl仅站内 `/...` 路径，不允许外部URL、协议相对URL、目录穿越或后端远程抓图。版本冲突返回业务码 `1070000409`，客户端必须提示重新加载/人工合并，不能覆盖他人改动。设备 sourceId 创建后不可修改；迁移至其他源须新建设备、重新确认映射并显式修改看板/工艺绑定，不提供有歧义的换源下拉框。code 是**租户内唯一的平台设备编码**，不是供应商原始设备名；不同源可以使用相同原始点名，但平台设备 code 仍需唯一。设备code与统一指标键限64字符（字母数字、下划线、点、短横线），映射最多100条，指标键唯一。

## 数据语义与已知限制

* 最多16个命名源，另保留可关闭的 legacy `default`。查询按 `sourceId` 分组，原始点名仅在组内去重；同名点跨源绝不合并、替换或回退。每源独立2个读取执行线程与2个排队位置，忙碌源返回 SOURCE_BUSY，不占其他源的池。所有组先并发提交，再按各自截止时间收集；及时完成的健康组保留，晚于自身截止时间的结果丢弃。HTTP响应仍须等本次全部组终态/到期，不是流式即时展示；最慢组不会串行推迟其他源开始执行。HTTP连接超时最多10秒、读取超时最多30秒，组截止预算为 `min(2×(连接+读取)+5秒,25秒)`，包含排队、可能的登录与读取；它可以早于单次HTTP读取超时到达，并会取消/丢弃逾期读取，避免超过正式前端30秒请求超时；不重放失败业务请求。该限制属于 IoT 适配层，不改变公共 starter 的66个操作或配置契约。
* 部分源失败时返回 `partial:true`、健康源 series 与失败源 errors；失败源没有虚构空/0数据。所有源失败（包括旧单源请求）返回业务码 `1070000002`，消息只列源ID与固定错误码。缺少设备/指标映射仍在发出任何请求前明确失败。上游错误正文、地址、账号与凭据不会返回浏览器。
* 限制：10设备、20指标、最多50序列，历史窗大于0且不超过7天，间隔至少1000ms，总采样点数不超过10000；对每组供应商实际返回的**聚合前**点数按该组请求采样预算再校验，超额源失败而不消耗其他源预算。请求超额明确失败，不静默截断。
* `RAW` 是 `getHistoryRange` **按intervalMs的供应商采样结果**，不承诺未采样全量。`AVG/MIN/MAX/LAST` 是**整个查询窗口**的单值聚合，不是每分钟/每桶聚合；曲线用RAW。AVG/MIN/MAX仅聚合数字且排除BAD/null，有质量问题则结果UNCERTAIN；LAST保留最后返回点。下载使用完全相同查询与聚合逻辑。
* 仅适配文档可验证的实时 `{items:[{name,val,time?,quality?}]}` 和历史 `{items:[{name,vals:[{time,val,quality?}]}]}`。未知形状失败关闭，不随意猜测字段；现场响应有差异时须提供脱敏样本并加契约测试后扩展。不是已经完成真实紫金桥服务器联调的声明。
* quality只识别明确字符串 GOOD/BAD/UNCERTAIN；未提供/未知/数字质量码=UNKNOWN，不猜测GOOD。值null= MISSING。缺少整个实时点返回null/MISSING；历史空序列返回`points:[]`，不补0、不插值、不制造采样点。
* 实时供应商未带time时使用接收时刻并返回 `timeSource:RECEIVED`，只能标注接收时间/采集时间未知，不能据此判断数据新鲜。供应商提供合法time时返回 `timeSource:DEVICE`。历史须有合法time。历史查询/导出的起止时间最多支持毫秒精度，超出精度时在查询上游或入队前返回业务参数错误 `1070000001`，不自动截断或四舍五入。API使用带offset时间；适配层按每个绑定数据源的zone独立转换供应商本地时间，返回UTC。跨夏令时切换或无法确定本地时间的请求/响应拒绝，不静默改时区。
* stat/gauge/table/image使用实时RAW；timeline仅RAW；只有line/bar可选择窗口聚合。后端保存配置时拒绝其他组件的非RAW组合，避免前端静默忽略。图表unit是展示标签；不做单位换算、设备状态推断、告警规则计算或图片热点控制操作。服务不实现逐设备ACL、实时推送与长期批量原始归档，这些不被包装成已有能力。

## 导出生命周期

任务持久化在关系库。入队时在同一事务锁定设备行（稳定ID顺序，与映射整体替换使用相同锁），保存不可变的设备ID、sourceId、源身份摘要、时区和已解析 pointName/指标/单位快照。worker使用快照，不重新解析最新映射；因此入队后映射变更不会悄悄改变下载含义。源身份摘要包含源ID、外部配置 revision、规范化地址、时区、认证模式与账号，不保存地址、账号、密码或token；密码/token轮换不改变身份。同URL背后替换现场实例，或token认证实际切换到不同上游账号/命名空间时，运维必须递增 revision；本层不会探测token主体来猜测身份变化。执行和下载都重新核对设备仍属于租户、绑定源未变、源仍启用/完整、租户仍有源权限及身份摘要一致；失效后明确失败/拒绝下载，绝不转读其他源。任一源失败时整份导出 FAILED，不发布不完整CSV。

旧设备通过迁移明确绑定 `default`，不会推测其属于某个新源。没有快照的旧 QUEUED/RUNNING 任务不能重建原始含义，迁移及worker均明确失败并提示重新创建。已经完成的旧CSV字节保持原样（旧表头也不篡改），下载仍校验租户/本人、所有设备仍绑定default及default当前权限/可用配置，不调用上游重生成。旧文件不补造不存在的来源快照。新任务的旧query_json字段只保存不可执行的版本标记，真实查询仅在snapshot_json中；即使旧worker误领新任务，也会在参数校验前后失败，不会按旧singleton/最新映射重新解释。这不代表支持新旧版本滚动共存：旧查询服务完全不理解device.sourceId，可能用singleton解释新命名源设备。**必须先停止/排空全部旧版IoT服务实例（不只是worker），再应用迁移、启动新版并创建命名源设备**；新旧服务禁止混跑，也不能直接降级继续运行已绑定多源的数据。主应用与数据中心子应用也应同步升级，并要求刷新旧标签页，避免旧界面忽略来源标识或部分失败信息。版本标记只是防止旧worker误领新任务的额外失败关闭保护，仍会将任务标失败。

每租户/用户最多3个QUEUED/RUNNING任务；数据库锁行跨实例串行准入。worker每2秒最多领1个任务，状态CAS抢占避免重复执行；取消运行中任务不保证中断已经发出的只读HTTP，但取消后结果不能发布。失败状态不保存含上游凭据/响应正文的异常信息。worker挂掉或超过10分钟仍RUNNING时标FAILED，不自动重放查询；用户可重新创建。

文件保存为私有DB文本，最多10000行/5MB，24小时过期（访问立即拒绝，worker清理正文），无公开URL，不调用默认公开infra文件接口。文件请求必须带token，不把token放下载URL。新CSV列为 `sourceId,deviceId,metricKey,unit,time,value,quality`；含UTF-8 BOM、转义引号并防公式注入，值null留空，质量列保留缺测。下载回查租户/本人/权限/设备存在与过期状态，`Cache-Control: no-store`。本版没有长周期元数据清理任务；生产部署需按业务保留期制定审核过的清理策略。

## 测试与验收

```bash
mvn -pl yudao-module-iot -am test
# 真实PostgreSQL必须是专用测试库：测试会清空iot_*测试表
DATABASE_TEST_URL=jdbc:postgresql://127.0.0.1:5432/yudao_test \
DATABASE_TEST_USERNAME=yudao_test DATABASE_TEST_PASSWORD='test-only' \
mvn -pl yudao-module-iot -am verify
```

测试类：`IotRepositoryTest` (H2 PostgreSQL模式，租户/版本/映射/模板/导出/取消竞态)、`IotPostgresqlTest` (继承同一契约、真实PostgreSQL)、`IotDashboardValidatorTest`、`IotQueryServiceTest` (mock上游，不伪称现场RealDB)、`IotControllerSecurityTest` (真实token/租户过滤器+方法鉴权，模拟远端鉴权服务)。CI要求PG类非skip并独立启动exec检查无凭据与伪造login-user都被拒绝。

本地/CI实际通过项以构建日志为准。缺少数据库、Redis、system/infra或真实RealDB时不可把静态检查/单元测试写成完整上线验收。此实现不执行真实库迁移、不生产部署、不改签名或访问凭据。

## 正式工艺页面与紫金桥数据源状态

正式工艺入口使用上述 `/iot/process-pages` 后端，不读取演示 bridge、演示设备或固定模拟测点。数据库迁移 `20261027_iot_process.sql` 将工艺配置单独保存到 `iot_process_page`；不是浏览器 localStorage，也不混入通用看板表。页面从空配置开始，允许明确的未绑定位置，不自动创建现场设备或测点。五种模板为四设备位置的可复用展示结构：油气水分离、天然气处理、储罐外输、公用工程、海上平台；它们**不是经过现场审核的 P&ID/工艺设计**，不提供自由管线设计、设备控制或工程安全联锁功能。

```ts
type Source = {
  id: string; name: string; type: 'REALDB'; enabled: boolean; configured: boolean;
  status: 'DISABLED' | 'NOT_CONFIGURED' | 'CONFIGURED';
  timeZone: string; description: string;
};
type ProcessScene = {
  id?: number; name: string; description?: string; version: number; schemaVersion: 1;
  template: 'separation' | 'gas' | 'storage' | 'utilities' | 'offshore';
  bindings: {
    slotId: 'slot-1' | 'slot-2' | 'slot-3' | 'slot-4';
    deviceId: number | null; metricKeys: string[]; primaryMetricKey: string;
  }[];
  refreshSeconds: number; staleAfterSeconds: number;
};
```

每租户最多100个工艺页面；创建时数据库锁行跨实例串行校验上限，超额明确拒绝，不会保存后在列表中悄悄截断。删除旧页面后可再次创建。

保存验证：名称非空最多100字符、描述最多500字符；恰好四个不同位置。未绑定位置须 `deviceId:null, metricKeys:[], primaryMetricKey:''`；绑定后选择1–20个不重复统一指标，主指标必须在所选指标内，每个设备和指标必须真实存在于当前租户配置，整页最多50个设备指标。统一指标键仍限64字符（字母数字、下划线、点、短横线），绝不把浏览器原始测点传入实时库。刷新周期整数5–300秒，过期阈值整数且不小于刷新周期、不大于3600秒。创建 version=0，更新与删除必须携带当前 version；冲突返回 `1070000409`，不会替用户覆盖别人的配置。路径ID不能被请求体替换，租户只取经过认证的上下文。

工艺读取复用 `iot:dashboard:query`，保存/删除复用 `iot:dashboard:configure`，源状态复用 `iot:device:query`，实时/历史与导出仍分别校验既有 `iot:data:query` / `iot:data:export`。本变更不新增权限、角色授权或匿名接口。只读用户可以查看已保存配置，但是否能读值仍由数据查询权限单独决定。指标映射此后被移除时，读值按现有映射解析规则明确失败，不换读其他指标、不填0；再次保存引用该指标的页面也会拒绝，需管理员修正绑定。

`GET /iot/sources` **只检查当前租户允许使用的配置元数据，不发起任何上游调用**。注册表不可由浏览器修改；页面既不提交URL/账号/密码/token，也不返回这些字段。`CONFIGURED` 仅意味着开启且基本连接/认证配置和客户端存在，不意味着网络可达、登录成功、测点授权正确或现场数据已验收。`DISABLED` 或 `NOT_CONFIGURED` 不会回退模拟值。某个**命名源**URL、时区、认证、超时或revision缺失/无效，只将该源标为NOT_CONFIGURED，不破坏其他源；非法/保留源ID或超过16个命名源则拒绝整个非法目录。源ID区分大小写，语法 `[A-Za-z0-9_.-]{1,64}`；源ID去除点号/短横线/下划线并转小写后也必须唯一（且不能等于default），防止Spring宽松属性绑定令不同源继承彼此字段；含点号/大写/下划线的YAML键请使用Spring Binder方括号保留形式，例如 `"[North.Site]"`。

旧 singleton `yudao.realdb.*`/`REALDB_*` 继续只代表保留源 `default`，**不允许命名配置覆盖default，也不回退到列表第一个源**。POST创建设备省略sourceId仅明确解释为default，若default已关闭/该租户不在允许范围则拒绝。为兼容旧安装，`yudao.iot.legacy-default-enabled` 默认true，`legacy-default-allowed-tenants` 未配置时保留旧租户+功能权限范围；显式空列表是deny-all，可配置具体ID收紧，或在多源新安装设置legacy-default-enabled=false完全隐藏/禁止该入口。这只是原单源兼容边界，不是对新源的自动授权。旧starter启用后的非法基础URL仍遵循原启动失败语义；本层不会偷偷替换该Bean或改变其他调用方的行为。新增命名源 `allowed-tenants` 缺省/空均deny-all，权限变更需要受控的部署配置与服务重启；本变更不自动执行任何实际授权。

部署配置示意（不是已建立现场连接；实际地址、账号与密码仅由运维在外部配置安全提供）：

```yaml
yudao:
  iot:
    legacy-default-enabled: false
    sources:
      site-a:
        name: 一号站紫金桥
        revision: site-a-v1 # 同地址背后更换现场实例时也须改变
        time-zone: Asia/Shanghai
        allowed-tenants: [] # 显式填入已审批的租户ID；此示例不授权任何租户
        connection:
          enabled: ${SITE_A_REALDB_ENABLED:false}
          base-url: ${SITE_A_REALDB_URL:}
          username: ${SITE_A_REALDB_USERNAME:}
          password: ${SITE_A_REALDB_PASSWORD:}
          token: ${SITE_A_REALDB_TOKEN:}
          security-enabled: true
          connect-timeout: 5s
          read-timeout: 30s
      site-b:
        name: 二号站紫金桥
        revision: site-b-v1
        time-zone: UTC
        allowed-tenants: []
        connection:
          enabled: ${SITE_B_REALDB_ENABLED:false}
          base-url: ${SITE_B_REALDB_URL:}
          username: ${SITE_B_REALDB_USERNAME:}
          password: ${SITE_B_REALDB_PASSWORD:}
          token: ${SITE_B_REALDB_TOKEN:}
          connect-timeout: 5s
          read-timeout: 30s
```

两源可以部署在不同服务器，拥有不同认证会话、超时与时区；A的登录/失效不会更新B的token，失败业务请求不跨源重试。没有自动探测网络、创建凭据、开通权限、生产部署或应用商店发布。legacy-default-revision 默认legacy-v1；使用自定义singleton Bean时，运维负责让其配置地址/时区/revision准确表示该Bean指向的实例，不能在相同revision后悄悄替换实现。

新增多源测试覆盖同名点路由、独立时区/DST、部分/全部失败、迟到响应、每源池饱和与超时、聚合前点数预算、租户源allowlist、独立HTTP登录/认证失效、配置不触网、映射变更后的排队导出快照、源身份/权限变更阻断、旧任务迁移和CSV来源。原测试继续覆盖五模板/空绑定/边界与坏输入、关系库持久化/租户/映射校验、同版本并发只成功一次、HTTP身份与查询/配置权限，以及源状态不调用上游和不泄漏秘密。`IotPostgresqlTest` 继承包括工艺页面在内的相同存储契约；缺少专用测试库时该类仍跳过，不能称已完成 PostgreSQL 验证。所有测试替身只用于测试，不能替代指定紫金桥版本、真实只读账号、点名清单及脱敏响应样本的现场验收。
