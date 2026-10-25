package cn.iocoder.yudao.framework.realdb.core.client;

import cn.iocoder.yudao.framework.realdb.core.dto.RealDbAlarmQueryDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbHistoryQueryDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbHistoryValueDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbReadOptionsDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbValueDTO;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 紫金桥 REST 客户端，对应 20260528 版文档的全部 66 个操作。
 *
 * 返回 JSON 保留供应商字段大小写、动态属性、根数组和不含 code 的响应。
 * 非零业务码或 HTTP 错误抛出 RealDbException；任何操作均不自动重放。
 * 一个实例对应一个服务账号，不应跨不同权限用户共享动态 token。
 */
public interface RealDbClient {

    /** 使用配置的账号重新登录。并发刷新会复用已经更新的 token，不重放业务请求。 */
    void refreshToken();

    /** 文档 3.1（第 9 页）：GET /api/userlogin；只返回登录结果，不替换配置服务账号的会话。 */
    JsonNode login(String username, String password);

    /** 文档 3.2（第 10 页）：GET /api/userlogout。 */
    JsonNode logout();

    /** 文档 4.1（第 11 页）：GET /api/realdata。 */
    JsonNode registerTags(String regname, List<String> names);

    /** 文档 4.2（第 12 页）：GET /api/realdata。 */
    JsonNode getRegisteredData(String regname, Integer decimal);

    /** 文档 4.3（第 12 页）：GET /api/realdir/{segments}。 */
    JsonNode getRealDirectory(List<String> segments);

    /** 文档 4.4（第 14 页）：GET /api/realdata/{segments}。 */
    JsonNode getNodeData(List<String> segments, RealDbReadOptionsDTO options);

    /** 文档 4.5（第 16 页）：GET /api/realdata。 */
    JsonNode getTagData(List<String> tags, List<String> pars, RealDbReadOptionsDTO options);

    /** 文档 4.6（第 18 页）：GET /api/realdata。 */
    JsonNode getNamedData(List<String> names, RealDbReadOptionsDTO options);

    /** 文档 4.7（第 20 页）：POST /api/realdata。 */
    JsonNode setRealData(List<RealDbValueDTO> values);

    /** 文档 4.8（第 21 页）：POST /api/realdata。 */
    JsonNode setRealDataArrays(List<String> names, List<?> values);

    /** 文档 4.9（第 22 页）：GET /api/hisdata。 */
    JsonNode getHistoryAtTimes(List<String> names, List<LocalDateTime> times, RealDbReadOptionsDTO options);

    /** 文档 4.10（第 24 页）：GET /api/hisdata。 */
    JsonNode getHistoryRange(RealDbHistoryQueryDTO request);

    /** 文档 4.11（第 26 页）：POST /api/hisdata。 */
    JsonNode setHistory(List<RealDbHistoryValueDTO> values);

    /** 文档 4.12（第 27 页）：GET /api/alarmdata。 */
    JsonNode getAlarms(RealDbAlarmQueryDTO request);

    /** 文档 4.13（第 29 页）：POST /api/SQL。 */
    JsonNode querySql(String sql);

    /** 文档 5.1.1（第 35 页）：GET /api/source。 */
    JsonNode listSources();

    /** 文档 5.1.2（第 36 页）：GET /api/source/{name}。 */
    JsonNode getSource(String name);

    /** 文档 5.1.3（第 37 页）：POST /api/source/add。 */
    JsonNode addSource(JsonNode body);

    /** 文档 5.1.4（第 38 页）：POST /api/source/update。 */
    JsonNode updateSource(JsonNode body);

    /** 文档 5.1.5（第 39 页）：DELETE /api/source。 */
    JsonNode deleteSources(List<String> names);

    /** 文档 5.2.1（第 39 页）：GET /api/user。 */
    JsonNode listUsers();

    /** 文档 5.2.2（第 40 页）：GET /api/user/{name}。 */
    JsonNode getUser(String name);

    /** 文档 5.2.3（第 41 页）：POST /api/user/add。 */
    JsonNode addUsers(JsonNode body);

    /** 文档 5.2.4（第 42 页）：POST /api/user/update。 */
    JsonNode updateUsers(JsonNode body);

    /** 文档 5.2.5（第 43 页）：DELETE /api/user。 */
    JsonNode deleteUsers(List<String> names);

    /** 文档 5.3.1（第 44 页）：GET /api/group。 */
    JsonNode listGroups();

    /** 文档 5.3.2（第 44 页）：GET /api/group/{name}。 */
    JsonNode getGroup(String name);

    /** 文档 5.3.3（第 45 页）：POST /api/group/add。 */
    JsonNode addGroups(JsonNode body);

    /** 文档 5.3.4（第 46 页）：POST /api/group/update。 */
    JsonNode updateGroups(JsonNode body);

    /** 文档 5.3.5（第 47 页）：DELETE /api/group。 */
    JsonNode deleteGroups(List<String> names);

    /** 文档 5.4.1（第 48 页）：GET /api/serial。 */
    JsonNode listSerials();

    /** 文档 5.4.2（第 48 页）：GET /api/serial/{name}。 */
    JsonNode getSerial(String name);

    /** 文档 5.4.3（第 49 页）：POST /api/serial/add。 */
    JsonNode addSerials(JsonNode body);

    /** 文档 5.4.4（第 50 页）：POST /api/serial/update。 */
    JsonNode updateSerials(JsonNode body);

    /** 文档 5.5.1（第 51 页）：GET /api/setting。 */
    JsonNode getSettings();

    /** 文档 5.5.2（第 52 页）：POST /api/setting/update。 */
    JsonNode updateSettings(JsonNode body);

    /** 文档 5.6.1（第 53 页）：GET /api/iosetting。 */
    JsonNode getIoSettings();

    /** 文档 5.6.2（第 55 页）：POST /api/iosetting/update。 */
    JsonNode updateIoSettings(JsonNode body);

    /** 文档 5.7.1（第 56 页）：GET /api/driver。 */
    JsonNode listDrivers();

    /** 文档 5.7.2（第 57 页）：GET /api/driver。 */
    JsonNode getDriver(String subdir, int subtype);

    /** 文档 5.8.1（第 71 页）：POST /api/dev/add。 */
    JsonNode addDevice(JsonNode body);

    /** 文档 5.8.2（第 76 页）：POST /api/dev/add/batch。 */
    JsonNode addDevices(JsonNode body);

    /** 文档 5.8.3（第 76 页）：DELETE /api/dev。 */
    JsonNode deleteDevices(List<String> names);

    /** 文档 5.8.4（第 77 页）：POST /api/dev/update。 */
    JsonNode updateDevice(JsonNode body);

    /** 文档 5.8.5（第 78 页）：POST /api/dev/update/batch。 */
    JsonNode updateDevices(JsonNode body);

    /** 文档 5.8.6（第 79 页）：GET /api/dev/{name}。 */
    JsonNode getDevice(String name);

    /** 文档 5.8.7（第 79 页）：GET /api/dev。 */
    JsonNode getDevices(List<String> names);

    /** 文档 5.8.8（第 80 页）：GET /api/dev。 */
    JsonNode listDevices();

    /** 文档 5.9.1（第 81 页）：GET /api/org/{segments}。 */
    JsonNode getDirectory(List<String> segments, Boolean recurve, Boolean withTag);

    /** 文档 5.9.2（第 83 页）：POST /api/org/add。 */
    JsonNode addDirectory(JsonNode body);

    /** 文档 5.9.3（第 85 页）：POST /api/org/update。 */
    JsonNode updateDirectory(JsonNode body);

    /** 文档 5.9.4（第 86 页）：DELETE /api/org/{segments}。 */
    JsonNode deleteDirectory(List<String> segments);

    /** 文档 5.10.1（第 86 页）：POST /api/tag/add。 */
    JsonNode addTag(JsonNode body);

    /** 文档 5.10.2（第 92 页）：POST /api/tag/add/batch。 */
    JsonNode addTags(JsonNode body);

    /** 文档 5.10.3（第 92 页）：POST /api/tag/update。 */
    JsonNode updateTag(JsonNode body);

    /** 文档 5.10.4（第 93 页）：POST /api/tag/update/batch。 */
    JsonNode updateTags(JsonNode body);

    /** 文档 5.10.5（第 94 页）：DELETE /api/tag。 */
    JsonNode deleteTags(List<String> names);

    /** 文档 5.10.6（第 95 页）：GET /api/tag。 */
    JsonNode listTags();

    /** 文档 5.10.7（第 96 页）：GET /api/tag/{name}。 */
    JsonNode getTag(String name);

    /** 文档 5.10.8（第 96 页）：GET /api/tag。 */
    JsonNode getTags(List<String> names);

    /** 文档 5.11.1（第 97 页）：GET /api/export。 */
    JsonNode listExports();

    /** 文档 5.11.2（第 98 页）：GET /api/export。 */
    JsonNode getExport(String name);

    /** 文档 5.11.3（第 99 页）：POST /api/export/add。 */
    JsonNode addExport(String name, JsonNode body);

    /** 文档 5.11.4（第 101 页）：POST /api/export。 */
    JsonNode updateExport(String name, JsonNode body);

    /** 文档 5.11.5（第 102 页）：DELETE /api/export。 */
    JsonNode deleteExportItems(String name, List<String> variables);

    /** 文档 5.11.6（第 102 页）：POST /api/export/synch。 */
    JsonNode synchronizeExport(String name);

}
