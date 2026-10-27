# 紫金桥实时数据库 REST 公共模块

## 范围与结构

`yudao-cloud-mini-master-jdk17/yudao-framework/yudao-spring-boot-starter-realdb` 是可独立复用的 Spring Boot Starter。包名采用项目现有规则 `cn.iocoder.yudao.framework.realdb`，分为 `config`、`core/client`、`core/dto`、`core/enums` 和 `core/exception`。

依据用户提供的《跨平台实时数据库 Rest API》（20260528 版，共 103 页），`RealDbClient` 对应 **66 个文档操作**：登录/登出 2 个、数据读写 13 个、运行时组态 51 个。同一 HTTP 路径通过参数区分多个操作，因此并非 66 个不同 URL。实现不依赖 PostgreSQL/人大金仓，不在业务数据库执行紫金桥 SQL，也不额外开放无需鉴权的 Web 转发接口。

核心入口：

- `config/RealDbProperties`：连接、认证、超时、发布项修改路径配置。
- `config/YudaoRealDbAutoConfiguration`：默认禁用的自动装配，可用自定义 `RealDbClient` Bean 替换。
- `core/client/RealDbClient`：全部接口契约。
- `core/client/DefaultRealDbClient`：JDK 17 `HttpClient` 实现，统一 URI 编码、认证和异常处理。
- `core/dto/*DTO`：实时值、历史值、历史查询、报警查询、读取选项。
- `core/enums/RealDbErrorCodeEnum`：文档 46 个错误码定义。
- `core/exception/RealDbException`：保留 HTTP 状态、业务码、JSON/原始响应和结果是否未知。

## 接入与配置

`yudao-framework/pom.xml` 已纳入该模块，`yudao-dependencies/pom.xml` 已管理版本，`yudao-server/pom.xml` 已引入依赖。其他微服务需要使用时，在相应 Maven 模块添加：

```xml
<dependency>
    <groupId>cn.iocoder.cloud</groupId>
    <artifactId>yudao-spring-boot-starter-realdb</artifactId>
</dependency>
```

在部署环境的外部配置或 Nacos 中启用：

```yaml
yudao:
  realdb:
    enabled: ${REALDB_ENABLED:false}
    base-url: ${REALDB_URL:http://127.0.0.1:8000}
    security-enabled: true
    username: ${REALDB_USERNAME:}
    password: ${REALDB_PASSWORD:}
    token: ${REALDB_TOKEN:}
    connect-timeout: 5s
    read-timeout: 30s
    sync-timeout: 190s
    update-export-path: /api/export
```

`base-url` 是 REST 服务地址，不包含末尾 `/api`；也可包含反向代理的固定前缀。仅支持 HTTP/HTTPS，禁止 URL 内的用户名、密码、查询参数和片段。HTTP 重定向不会被跟随，以免 token 被转发到其他站点。组件建立时只校验配置，不连接实时库；首次需要认证的操作才登录。

`enabled` 缺省为 `false`。启用时 `base-url` 必填。`token` 与账号密码可以任选其一，也可同时配置；现成 token 优先使用，有账号密码时可在失效后重新登录。未启用模块时不会生成 `RealDbClient` Bean，按条件启用调用该客户端的业务组件：

```java
import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbReadOptionsDTO;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "yudao.realdb", name = "enabled", havingValue = "true")
public class RealDbService {
    private final RealDbClient realDbClient;

    public JsonNode getTemperatures() {
        return realDbClient.getNamedData(List.of("A1.PV", "A2.PV"),
                new RealDbReadOptionsDTO().setDecimal(2).setValueOnly(false));
    }
}
```

服务端需完成 REST 授权、启动 REST 服务，并配置相应账号的点读写/组态权限。文档默认端口为 8000。客户端不会替代服务端权限管理。

## 协议处理约定

### 认证与并发

所有 token 都放在 URL **查询参数** `token` 中，遵循供应商协议；不使用 Bearer Header。数据读写在 `security-enabled=false` 时可省略 token，但注册点名、读取注册项和全部运行时组态始终需要认证。

一个客户端实例代表一个服务账号。懒登录受锁保护，并发首次访问只登录一次；不要将多个权限用户的会话混入一个实例。需要按终端用户代理时，应由业务层按用户隔离实例，并执行现有权限校验。

- `login(username, password)` 是文档登录操作，只返回响应，不替换配置服务账号的缓存 token。
- `logout()` 登出当前配置会话并清除本地缓存；若配置了账号密码，下一次需要认证的显式调用重新登录。
- `refreshToken()` 使用配置账号主动刷新；同时开始的刷新会复用已经更新的会话。
- HTTP 401/403 或认证类业务错误会使相应缓存失效。**不重放失败的业务请求**，下次调用才尝试认证。
- 文档中的 token 没有定时过期机制，所以不设猜测性的周期刷新。只配置 token 而未配置账号密码时，失效后需提供新的 token/重建客户端。

组件不记录请求 URL、密码、token 或响应正文；异常链也不附带可能包含完整 URL 的底层传输异常。运维的代理/访问日志同样应避免记录这些认证查询参数。

### 参数与 JSON

名称列表由 `List<String>` 转换为半角逗号分隔值，名称本身不能含逗号。层级路径由 `List<String>` 表示，各段分别进行 UTF-8 百分号编码；空列表代表根目录。空路径段及 `.`、`..` 被拒绝。查询值中的中文、空格、`&`、`+` 等不会注入额外参数。

时间使用 `LocalDateTime`，发送格式固定为 `yyyy-MM-dd'T'HH:mm:ss.SSS`，代表**紫金桥服务器当地时间**，组件不自动做时区转换；超过毫秒精度会拒绝，防止隐式截断。历史间隔单位为毫秒，必须大于 0；开始时间不得晚于结束时间。报警查询的开始和结束时间须同时提供。

实时/历史值保留数字与字符串类型，拒绝 null、嵌套对象、数组和 NaN/Infinity。`decimal` 范围为 0–9，`valueOnly`、`recursion`、`recurve` 和 `withTag` 转换为供应商要求的 0/1。

运行时组态包含依驱动/点类型变化的字段、`#value` 和不一致的大小写。因此这些操作采用 Jackson `JsonNode` 参数，不做 DTO 字段白名单裁剪；对象与数组形状会在发出请求前校验。批量用户、用户组、串口、设备、点操作传**根 JSON 数组**；单个数据源/设备/点传对象。返回也用 `JsonNode` 原样保留结构与字段大小写；浮点数用 `BigDecimal` 解析，避免额外损失精度。

成功响应可能是根数组、`{items:...}`、列数组或普通对象，且部分响应没有 `code`；组件支持这些形式，不套项目 `CommonResult`。如需业务控制器返回统一包装，请在业务层包装，并保留实时库协议语义。

### 错误、批量部分成功与超时

顶层非零 `code`、串口等根数组中的逐项非零 `code`、非 2xx 状态均抛出 `RealDbException`。`code` 既兼容数字也兼容数字字符串。异常 `response` 保留完整数组，调用方能分辨批量哪些项已成功；不要盲目重交整个数组。HTTP 错误可能不含 JSON，此时使用 `httpStatus` 和 `rawResponse`。

```java
try {
    realDbClient.synchronizeExport("opcua");
} catch (RealDbException exception) {
    if (exception.isOutcomeUnknown()) {
        // 在业务流程标记待核查，读取服务端实际状态；不自动重放。
    }
    // 可读取 getHttpStatus()/getCode()/getResponse() 做明确处理。
    // 响应可能包含组态密码，不要直接把异常的响应字段写日志。
    throw exception;
}
```

同步发布项是 `POST /api/export/synch`，**不发请求体**。服务端最多等待 180 秒，默认单独设置 190 秒客户端超时。HTTP 406 可能没有常规响应体，此时同步仍可能在服务端继续执行，所以会标记 `outcomeUnknown=true`。通信中断、超时和无法解析的成功响应同样无法证明写操作未执行。所有操作都没有应用层自动重试，包括会改变状态的 GET 注册、GET 登出。

文档建议一次发布项调整不要超过 800 点，实际容量与服务器性能有关。接口没有声明通用分页机制，组件不擅自切分批量请求或历史查询，以免改变顺序、部分成功和同步语义；调用方应按服务器配置的查询/写入上限分批安排业务。

## IoT 多安装注册表（不改变本starter的66操作契约）

`yudao-module-iot` 的 `IotSourceRegistry` 在应用层组织多个独立 `DefaultRealDbClient`。配置位置是 `yudao.iot.sources.<sourceId>.connection.*`，连接字段沿用本节 `RealDbProperties`；每个命名源另外声明 `name`、`time-zone`、`revision` 与明确的 `allowed-tenants`。每个实例独立HTTP客户端、认证缓存、token锁和超时，构造/目录读取不发起登录或探测。IoT仍只调用只读数据接口，没有向浏览器开放本starter的写值、SQL、控制或组态操作。

原 `yudao.realdb.*` 自动装配/自定义Bean仍保持向后兼容，对应IoT保留源 `default`。命名源不注册成同类型Spring单例Bean、不争抢/覆盖旧Bean，不会改变其他微服务注入原 `RealDbClient` 的行为。新源的URL与凭据仅来自部署配置，浏览器只传平台设备ID/统一指标，绑定源创建后不可更改。目录缺省deny-all（旧default兼容范围例外需显式管理）；sourceId不等于供应商 `/api/source` 组态数据源，它是平台命名的**独立紫金桥服务器连接**。

IoT每源最多2个并发任务+2个排队位置，连接超时上限10秒、读取上限30秒，IoT整组操作另有25秒硬截止（含排队/登录/读取，可能早于单次HTTP超时）；这些IoT安全预算不会缩小starter独立使用时的协议范围或同步发布190秒默认超时。查询/导出逐源转换时区与隔离错误，同名点只在同一个源内去重，无跨源fallback。完整部署示例、授权边界、迁移、部分失败与不可变导出快照详见 [IoT后端契约](iot-backend.md)。这些都是受控loopback HTTP/数据库测试，不是已接通用户现场服务器的声明。

## 66 个操作对应表

下表的查询参数省略通用 `token`。表中 `segments` 代表经逐段编码的层级路径。参数的必填性见后面的调用与组态说明，Java 方法签名在 `RealDbClient` 中。

| 文档节 / 页 | Java 方法 | HTTP | 路径 | 查询参数 | 请求体 |
| --- | --- | --- | --- | --- | --- |
| 3.1 / 9 | `login` | GET | `/api/userlogin` | `user`, `pass` | 无 |
| 3.2 / 10 | `logout` | GET | `/api/userlogout` | 无 | 无 |
| 4.1 / 11 | `registerTags` | GET | `/api/realdata` | `regname`, `names` | 无 |
| 4.2 / 12 | `getRegisteredData` | GET | `/api/realdata` | `regname`, `decimal` | 无 |
| 4.3 / 12 | `getRealDirectory` | GET | `/api/realdir/{segments}` | 无 | 无 |
| 4.4 / 14 | `getNodeData` | GET | `/api/realdata/{segments}` | `recursion`, `valueonly`, `decimal` | 无 |
| 4.5 / 16 | `getTagData` | GET | `/api/realdata` | `tags`, `pars`, `valueonly`, `decimal` | 无 |
| 4.6 / 18 | `getNamedData` | GET | `/api/realdata` | `names`, `valueonly`, `decimal` | 无 |
| 4.7 / 20 | `setRealData` | POST | `/api/realdata` | 无 | {items:[{name:string,val:number / string}]} |
| 4.8 / 21 | `setRealDataArrays` | POST | `/api/realdata` | 无 | {names:[string],vals:[number / string]} |
| 4.9 / 22 | `getHistoryAtTimes` | GET | `/api/hisdata` | `times`, `names`, `decimal`, `valueonly` | 无 |
| 4.10 / 24 | `getHistoryRange` | GET | `/api/hisdata` | `startTime`, `endTime`, `interval`, `names`, `decimal`, `valueonly` | 无 |
| 4.11 / 26 | `setHistory` | POST | `/api/hisdata` | 无 | {items:[{name,vals:[{time,val}]}]} |
| 4.12 / 27 | `getAlarms` | GET | `/api/alarmdata` | `realalmcount`, `org`, `unit`, `level`, `tags`, `groupbytag`, `startTime`, `endTime` | 无 |
| 4.13 / 29 | `querySql` | POST | `/api/SQL` | 无 | {query:string} |
| 5.1.1 / 35 | `listSources` | GET | `/api/source` | 无 | 无 |
| 5.1.2 / 36 | `getSource` | GET | `/api/source/{name}` | 无 | 无 |
| 5.1.3 / 37 | `addSource` | POST | `/api/source/add` | 无 | source object |
| 5.1.4 / 38 | `updateSource` | POST | `/api/source/update` | 无 | source object |
| 5.1.5 / 39 | `deleteSources` | DELETE | `/api/source` | `name` | 无 |
| 5.2.1 / 39 | `listUsers` | GET | `/api/user` | 无 | 无 |
| 5.2.2 / 40 | `getUser` | GET | `/api/user/{name}` | 无 | 无 |
| 5.2.3 / 41 | `addUsers` | POST | `/api/user/add` | 无 | [user object] |
| 5.2.4 / 42 | `updateUsers` | POST | `/api/user/update` | 无 | [user object] |
| 5.2.5 / 43 | `deleteUsers` | DELETE | `/api/user` | `name` | 无 |
| 5.3.1 / 44 | `listGroups` | GET | `/api/group` | 无 | 无 |
| 5.3.2 / 44 | `getGroup` | GET | `/api/group/{name}` | 无 | 无 |
| 5.3.3 / 45 | `addGroups` | POST | `/api/group/add` | 无 | [group object] |
| 5.3.4 / 46 | `updateGroups` | POST | `/api/group/update` | 无 | [group object] |
| 5.3.5 / 47 | `deleteGroups` | DELETE | `/api/group` | `name` | 无 |
| 5.4.1 / 48 | `listSerials` | GET | `/api/serial` | 无 | 无 |
| 5.4.2 / 48 | `getSerial` | GET | `/api/serial/{name}` | 无 | 无 |
| 5.4.3 / 49 | `addSerials` | POST | `/api/serial/add` | 无 | [{BAUD,DATABIT,NAME,PARITY,STOPBIT}] |
| 5.4.4 / 50 | `updateSerials` | POST | `/api/serial/update` | 无 | [{BAUD,DATABIT,NAME,PARITY,STOPBIT}] |
| 5.5.1 / 51 | `getSettings` | GET | `/api/setting` | 无 | 无 |
| 5.5.2 / 52 | `updateSettings` | POST | `/api/setting/update` | 无 | settings object |
| 5.6.1 / 53 | `getIoSettings` | GET | `/api/iosetting` | 无 | 无 |
| 5.6.2 / 55 | `updateIoSettings` | POST | `/api/iosetting/update` | 无 | ioSettings object |
| 5.7.1 / 56 | `listDrivers` | GET | `/api/driver` | 无 | 无 |
| 5.7.2 / 57 | `getDriver` | GET | `/api/driver` | `subdir`, `subtype` | 无 |
| 5.8.1 / 71 | `addDevice` | POST | `/api/dev/add` | 无 | device object |
| 5.8.2 / 76 | `addDevices` | POST | `/api/dev/add/batch` | 无 | [device object] |
| 5.8.3 / 76 | `deleteDevices` | DELETE | `/api/dev` | `name` | 无 |
| 5.8.4 / 77 | `updateDevice` | POST | `/api/dev/update` | 无 | device object |
| 5.8.5 / 78 | `updateDevices` | POST | `/api/dev/update/batch` | 无 | [device object] |
| 5.8.6 / 79 | `getDevice` | GET | `/api/dev/{name}` | 无 | 无 |
| 5.8.7 / 79 | `getDevices` | GET | `/api/dev` | `names` | 无 |
| 5.8.8 / 80 | `listDevices` | GET | `/api/dev` | 无 | 无 |
| 5.9.1 / 81 | `getDirectory` | GET | `/api/org/{segments}` | `recurve`, `withtag` | 无 |
| 5.9.2 / 83 | `addDirectory` | POST | `/api/org/add` | 无 | {Name:string,PATH:string,DESC:string} |
| 5.9.3 / 85 | `updateDirectory` | POST | `/api/org/update` | 无 | {Name:string,PATH:string,NewName:string,DESC:string} |
| 5.9.4 / 86 | `deleteDirectory` | DELETE | `/api/org/{segments}` | 无 | 无 |
| 5.10.1 / 86 | `addTag` | POST | `/api/tag/add` | 无 | tag object; depends on point kind |
| 5.10.2 / 92 | `addTags` | POST | `/api/tag/add/batch` | 无 | [tag object] |
| 5.10.3 / 92 | `updateTag` | POST | `/api/tag/update` | 无 | tag object |
| 5.10.4 / 93 | `updateTags` | POST | `/api/tag/update/batch` | 无 | [tag object] |
| 5.10.5 / 94 | `deleteTags` | DELETE | `/api/tag` | `name` | 无 |
| 5.10.6 / 95 | `listTags` | GET | `/api/tag` | 无 | 无 |
| 5.10.7 / 96 | `getTag` | GET | `/api/tag/{name}` | 无 | 无 |
| 5.10.8 / 96 | `getTags` | GET | `/api/tag` | `name` | 无 |
| 5.11.1 / 97 | `listExports` | GET | `/api/export` | 无 | 无 |
| 5.11.2 / 98 | `getExport` | GET | `/api/export` | `name` | 无 |
| 5.11.3 / 99 | `addExport` | POST | `/api/export/add` | `name` | {path?:string,tag?:[{desc,name}],item?:object / array} |
| 5.11.4 / 101 | `updateExport` | POST | `/api/export` | `name` | {path?:string,tag?:[{desc,name}],item?:object / array} |
| 5.11.5 / 102 | `deleteExportItems` | DELETE | `/api/export` | `name`, `vars` | 无 |
| 5.11.6 / 102 | `synchronizeExport` | POST | `/api/export/synch` | `name` | 无 |

## 数据读写调用示例

```java
// 注册名是服务账号私有的。即使安全管理关闭，此操作仍需要登录。
realDbClient.registerTags("temperatures", List.of("A1.PV", "A2.PV"));
JsonNode current = realDbClient.getRegisteredData("temperatures", 2);

// 设置实时数据，JSON 使用供应商要求的 val，而不是 DTO 的 value。
realDbClient.setRealData(List.of(
        new RealDbValueDTO().setName("A1.PV").setValue(42.5),
        new RealDbValueDTO().setName("A2.DESC").setValue("入口温度")));

// 设置实时数据的并行数组形式。
realDbClient.setRealDataArrays(List.of("A1.PV", "A2.PV"), List.of(42.5, 38.0));

// 指定时间点与时间区间是两个独立方法，均采用服务器当地时间。
LocalDateTime start = LocalDateTime.of(2026, 5, 28, 10, 0);
JsonNode atTimes = realDbClient.getHistoryAtTimes(List.of("A1.PV"),
        List.of(start, start.plusSeconds(1)), new RealDbReadOptionsDTO().setValueOnly(true));
JsonNode history = realDbClient.getHistoryRange(new RealDbHistoryQueryDTO()
        .setNames(List.of("A1.PV", "A2.PV"))
        .setStartTime(start).setEndTime(start.plusMinutes(1)).setInterval(1000L)
        .setOptions(new RealDbReadOptionsDTO().setDecimal(2)));

realDbClient.setHistory(List.of(new RealDbHistoryValueDTO().setName("A1.PV").setValues(
        List.of(new RealDbHistoryValueDTO.Value().setTime(start).setValue(42.5)))));

JsonNode alarms = realDbClient.getAlarms(new RealDbAlarmQueryDTO()
        .setTags(List.of("A1", "A2")).setLevel(1).setRealAlarmCount(100));

// 发送到紫金桥自身 /api/SQL，路径 SQL 必须大写；不是业务 PostgreSQL 的语法。
JsonNode columns = realDbClient.querySql("select * from RealData where kind<100");
```

`getTagData(tags, pars, options)` 将点名与属性分开；其 `valueOnly=true` 返回按属性命名的数组。`getNamedData(names, options)` 使用 `A1.PV` 形式，其 `valueOnly=true` 返回按名称顺序排列的 `items` 数组。`getNodeData(segments, options)` 才使用 `options.recursion`；其他方法不发送该字段。历史值通常返回 `items[].name/vals`，`vals` 在只取值模式下是纯数值/字符串数组。

## 运行时组态 JSON 与返回形状

下列字段保留供应商大小写，不转换成 Java 命名方式。服务端为最终业务参数校验方，组件保留未列出的驱动扩展字段。

| 对象 | 常用字段 / 子结构 | 输入与返回要点 |
| --- | --- | --- |
| 数据源 | `name, cmt, addr, addr2, addrspare, addrspare2, port, port2, type, cyc, timeout, default, compress, encrypt, login, user, pass, nodename, recover, recoverThreadCount, recoverlimit, reversedAgent, usesecond` | 增/改单对象；列表直接返回 `[{name,desc}]`，详情是对象；删除用 `name` 逗号串。 |
| 用户 | `name, cmt, admin, changePassAble, disable, level, oper, pass, passnolimit, retryCount, timeout, verify` | 增/改根对象数组；列表 `[{name,cmt}]`，详情对象；删除用 `name`。 |
| 用户组 | `name, cmt, ModRecipe, SetData, _users, exitRun, intoConfig` | 增/改根对象数组；详情对象；保留 `ModRecipe`、`SetData` 首字母大写。 |
| 串口 | `BAUD, DATABIT, NAME, PARITY, STOPBIT` | 列表字符串数组，详情示例是对象数组；增/改均对象数组，返回逐项 `[{code,name}]`；update 可新增不存在串口。 |
| 系统参数 | `HisBlockSize, HisSaveDays, InsertBadToHis, KeepLastToConfig, cyc, disabledataset, keeplastcyc, parallelHand, port, port2, userport2` | GET 返回对象，POST 更新对象。 |
| IO 参数 | `IoProcMulti, IoSplitEnable, cyc, devmax, disabledataset, dispInTray, runmonitor, sockCntPerThread, threadcount, IoSource` | `IoSource` 为数据源对象，保持嵌套结构。 |
| 驱动 | `#value, CUSTOMPARS, DEVBASE, ENUMTABLE, INITVALUES, LINKITEMSDEF, LABEL, LINKTYPE, NAME, SUBDIR, SUBTYPE, USESECOND` | 列表返回包含 `cat/Item/Protocol` 的数组；详情含驱动自定义元数据，可混用字符串、数值、对象和数组。 |
| 设备 | `NAME, LABEL, LINKTYPE, NETADDR, NETADDR2, NETADDRSPARE, NETADDRSPARE2, NETPORT, NETPORT2, NETPORTSPARE, NETPORTSPARE2, SUBDIR, SUBTYPE, USESECOND, DEVBASE, CUSTOMPARS` | 单个增/改对象、批量增/改根数组；GET 批量参数是 **names**；列表返回 `{items:[{name,desc}]}`。 |
| 点目录 | 新增：`Name, PATH, DESC`；修改另有 `NewName` | `PATH` 在请求体中用点分层级；GET/DELETE 则用 `/` 路径段；GET 递归参数名为 **recurve**。返回 `Dir/Tag` 可为对象或数组。 |
| 模拟点 | `Name, Desc, Kind, TagDir, UNIT, EU, EUHI, EULO, FORMAT, PV, PVRAW, PVRAWHI, PVRAWLO, LINEFL, LINETAB, OVERCUT, SCALEFL, SECUBIT, SecuReads, SecuWrites, SENS, STATIS, ALARM, HIS, LINK` | `ALARM` 含 `HH/HI/LL/LO/DEV/RATE`，`HIS` 按属性组织，`LINK` 内容随驱动变化；点目录 `TagDir` 示例用 `Root/Dir1`。 |
| 数字点 | `NAME, DESC, KIND, OFFMES, ONMES, PARENTID, TAGID, TagDir, UNIT, PV, SECUBIT, SecuReads, SecuWrites, SENS, ALARM, HIS, LINK` | `ALARM.NORMALVAL` 与模拟点报警结构不同；单个增/改对象，批量增/改根数组；GET 批量查询参数是 **name**。 |
| 发布项 | `path, tag:[{name,desc}], item`，`item` 内可递归包含 `tag/item` | `name` 查询参数通常为 `opcda/opcua/web/rest`；`path` 目标目录需存在；`item` 可按服务器版本为对象或数组。 |

设备 `DEVBASE` 常见字段为 `ADDR, BROADCAST, DESC, DISABLE, DLLTEMPL, DYNAMICOPT, IGNORETIMEOUT, IoServerName, MULTICHANNEL, PACKFORMAT, PACKLEN, REMOTE, RETRYCOUNT, RETRYCYC, RETRYLIMIT, TIMEOUT, UPDATECYC, VERSION, WITHADDR, WRITEFREE, WRITEVARIANT`。`CUSTOMPARS` 以实际驱动定义为准，例如 Modbus 的字节顺序、包长、寄存器编码等，组件不会对其做有损映射。

组态代码示例：

```java
ObjectMapper mapper = new ObjectMapper();
ObjectNode directory = mapper.createObjectNode()
        .put("Name", "factory1").put("PATH", "root").put("DESC", "一号工厂");
JsonNode created = realDbClient.addDirectory(directory); // {code,ID}
JsonNode tree = realDbClient.getDirectory(List.of("root", "factory1"), true, true);

// 使用服务器返回的完整驱动/设备/点结构再修改所需字段，保留未知属性。
ObjectNode device = (ObjectNode) realDbClient.getDevice("modbus1").deepCopy();
device.put("NETADDR", "192.0.2.10");
realDbClient.updateDevice(device);

ObjectNode exported = mapper.createObjectNode().put("path", "");
exported.putArray("tag").addObject().put("name", "A1.PV").put("desc", "入口温度");
realDbClient.addExport("opcua", exported);
realDbClient.synchronizeExport("opcua");
```

## 文档歧义与实现决策

1. **第 101 页，5.11.4 修改发布项 URL**：原始 PDF 经页面渲染确认，写为 `/api/export/name={ExportName}`，请求示例也缺少 `?`，但参数表明确 `name` 为查询参数。本组件默认采用 `POST /api/export?name=...`，并提供 `update-export-path` 配置；如服务版本实际使用 `/api/export/name=opcua`，可设置 `/api/export/name={name}`。两种路由不会自动探测或自动重复写入。必须在目标紫金桥版本上确认该路由。
2. **第 85 页目录修改字段**：参数表写 `PATH/DESC`，示例写 `Path/desc`。使用原样 JSON；调用方可按所用版本提供对应字段。本文示例以参数表为准。
3. **第 92–97 页点批量示例**：若干示例写成非法 JSON `{ [ ... ] }`。按正文“对象数组”实现为根 JSON 数组；单点更新按标题及正文为对象，保留独立批量方法。
4. **设备详情第 79–80 页**：正文描述设备对象/数组，后面又附有多余 `{code:0}` 示例。组件不固定响应 DTO，保留服务实际返回的 JSON。
5. **对象/数组不一致**：目录 `Dir/Tag`、发布项 `item` 的表格写数组，但例子可为对象。用 `JsonNode` 同时支持，不强制归一化。
6. **类型及大小写笔误**：数据源删除 `name` 被写为 Integer，用户 `cmt` 被写为 Integer，某些 `code` 写 String，而样例使用字符串名称、文本说明及数值错误码。名称按字符串处理，`code` 接受数字及数字字符串；其余配置字段保持调用方 JSON。
7. **驱动元数据与设备实际枚举不同**：部分元数据使用 1/2，而设备实际配置使用 0/1。组件不盲目转换驱动字段，按设备配置示例与目标驱动实测填写。

## 验证与运行边界

在后端目录运行：

```bash
mvn -pl yudao-framework/yudao-spring-boot-starter-realdb -am test
```

测试用本机 HTTP 服务实际接收 JDK 客户端请求，覆盖全部 66 个操作的 HTTP 方法、路径、查询参数和 JSON/空请求体；另外验证 UTF-8 与特殊字符编码、响应根数组/无 code 对象、业务及部分批量错误、失效后下次调用认证、并发首次登录仅一次、显式登录不污染服务账号、登出、数值精度、同步独立超时、空响应 HTTP 406、写入超时不重试、输入校验、默认禁用与替换发布路径。

这些验证证明本组件按文档构造和处理协议，**不代表已连接真实紫金桥服务**。本次未提供紫金桥运行实例、版本账号和现场点数据，因此实际驱动参数、点/目录权限、服务端容量及第 101 页路由需要部署环境联调。所有接入均限于显式启用的外部服务配置。
