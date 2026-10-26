package cn.iocoder.yudao.framework.realdb.core.client;

import cn.iocoder.yudao.framework.realdb.core.dto.RealDbAlarmQueryDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbHistoryQueryDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbHistoryValueDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbReadOptionsDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbValueDTO;
import com.fasterxml.jackson.databind.JsonNode;

import cn.iocoder.yudao.framework.realdb.config.RealDbProperties;
import cn.iocoder.yudao.framework.realdb.core.exception.RealDbException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 紫金桥 REST 客户端默认实现，传输不依赖应用数据库及 Web 容器。 */
public class DefaultRealDbClient implements RealDbClient {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final RealDbProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Object tokenLock = new Object();
    private volatile String token;

    public DefaultRealDbClient(RealDbProperties properties, ObjectMapper mapper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.baseUrl = validateBaseUrl(properties.getBaseUrl());
        positive(properties.getConnectTimeout(), "connectTimeout");
        positive(properties.getReadTimeout(), "readTimeout");
        positive(properties.getSyncTimeout(), "syncTimeout");
        String exportPath = properties.getUpdateExportPath();
        if (exportPath == null || !exportPath.replace("{name}", "name").matches("/api/[A-Za-z0-9_./=~-]+")
                || exportPath.contains("..") || exportPath.contains("//")) {
            throw new IllegalArgumentException("updateExportPath 必须为 /api/ 下的相对路径，可包含 {name}");
        }
        this.httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        this.token = hasText(properties.getToken()) ? properties.getToken() : null;
    }

    @Override
    public void refreshToken() {
        String previous = token;
        synchronized (tokenLock) {
            if (!Objects.equals(previous, token) && token != null) {
                return;
            }
            token = authenticate();
        }
    }

    @Override
    public JsonNode login(String username, String password) {
        Map<String, String> query = query("user", required(username, "username"));
        if (password == null) {
            throw new IllegalArgumentException("password 不能为 null");
        }
        query.put("pass", password);
        return execute("GET", "/api/userlogin", query, null, false, properties.getReadTimeout());
    }

    @Override
    public JsonNode logout() {
        String current = requireToken();
        try {
            return exchange("GET", "/api/userlogout", query("token", current), null,
                    properties.getReadTimeout(), current);
        } finally {
            invalidateToken(current);
        }
    }

    @Override
    public JsonNode registerTags(String regname, List<String> names) {
        Map<String, String> query = query("regname", required(regname, "regname"));
        query.put("names", names(names));
        return get("/api/realdata", query, true);
    }

    @Override
    public JsonNode getRegisteredData(String regname, Integer decimal) {
        Map<String, String> query = query("regname", required(regname, "regname"));
        putDecimal(query, decimal);
        return get("/api/realdata", query, true);
    }

    @Override
    public JsonNode getRealDirectory(List<String> segments) {
        return get(path("/api/realdir", segments), Map.of(), properties.isSecurityEnabled());
    }

    @Override
    public JsonNode getNodeData(List<String> segments, RealDbReadOptionsDTO options) {
        Map<String, String> query = options(options);
        if (options != null) {
            putFlag(query, "recursion", options.getRecursion());
        }
        return get(path("/api/realdata", segments), query, properties.isSecurityEnabled());
    }

    @Override
    public JsonNode getTagData(List<String> tags, List<String> pars, RealDbReadOptionsDTO options) {
        Map<String, String> query = options(options);
        query.put("tags", names(tags));
        query.put("pars", names(pars));
        return get("/api/realdata", query, properties.isSecurityEnabled());
    }

    @Override
    public JsonNode getNamedData(List<String> names, RealDbReadOptionsDTO options) {
        Map<String, String> query = options(options);
        query.put("names", names(names));
        return get("/api/realdata", query, properties.isSecurityEnabled());
    }

    @Override
    public JsonNode setRealData(List<RealDbValueDTO> values) {
        nonEmpty(values, "values");
        ArrayNode items = mapper.createArrayNode();
        for (RealDbValueDTO value : values) {
            Objects.requireNonNull(value, "value");
            ObjectNode item = items.addObject().put("name", required(value.getName(), "name"));
            item.set("val", scalar(value.getValue()));
        }
        return post("/api/realdata", Map.of(), mapper.createObjectNode().set("items", items),
                properties.isSecurityEnabled());
    }

    @Override
    public JsonNode setRealDataArrays(List<String> names, List<?> values) {
        names(names);
        nonEmpty(values, "values");
        if (names.size() != values.size()) {
            throw new IllegalArgumentException("names 和 values 数量必须一致");
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("names", mapper.valueToTree(names));
        ArrayNode vals = body.putArray("vals");
        values.forEach(value -> vals.add(scalar(value)));
        return post("/api/realdata", Map.of(), body, properties.isSecurityEnabled());
    }

    @Override
    public JsonNode getHistoryAtTimes(List<String> names, List<LocalDateTime> times, RealDbReadOptionsDTO options) {
        nonEmpty(times, "times");
        Map<String, String> query = options(options);
        query.put("names", names(names));
        query.put("times", times.stream().map(DefaultRealDbClient::time).collect(Collectors.joining(",")));
        return get("/api/hisdata", query, properties.isSecurityEnabled());
    }

    @Override
    public JsonNode getHistoryRange(RealDbHistoryQueryDTO request) {
        Objects.requireNonNull(request, "request");
        checkRange(request.getStartTime(), request.getEndTime());
        if (request.getInterval() == null || request.getInterval() <= 0) {
            throw new IllegalArgumentException("interval 必须大于 0 毫秒");
        }
        Map<String, String> query = options(request.getOptions());
        query.put("names", names(request.getNames()));
        query.put("startTime", time(request.getStartTime()));
        query.put("endTime", time(request.getEndTime()));
        query.put("interval", String.valueOf(request.getInterval()));
        return get("/api/hisdata", query, properties.isSecurityEnabled());
    }

    @Override
    public JsonNode setHistory(List<RealDbHistoryValueDTO> values) {
        nonEmpty(values, "values");
        ArrayNode items = mapper.createArrayNode();
        for (RealDbHistoryValueDTO value : values) {
            Objects.requireNonNull(value, "value");
            ObjectNode item = items.addObject().put("name", required(value.getName(), "name"));
            nonEmpty(value.getValues(), "history values");
            ArrayNode vals = item.putArray("vals");
            for (RealDbHistoryValueDTO.Value entry : value.getValues()) {
                Objects.requireNonNull(entry, "history value");
                vals.addObject().put("time", time(entry.getTime())).set("val", scalar(entry.getValue()));
            }
        }
        return post("/api/hisdata", Map.of(), mapper.createObjectNode().set("items", items),
                properties.isSecurityEnabled());
    }

    @Override
    public JsonNode getAlarms(RealDbAlarmQueryDTO request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> query = new LinkedHashMap<>();
        if (request.getRealAlarmCount() != null && request.getRealAlarmCount() <= 0) {
            throw new IllegalArgumentException("realAlarmCount 必须大于 0");
        }
        put(query, "realalmcount", request.getRealAlarmCount());
        put(query, "org", request.getOrg());
        put(query, "unit", request.getUnit());
        put(query, "level", request.getLevel());
        if (request.getTags() != null) {
            query.put("tags", names(request.getTags()));
        }
        putFlag(query, "groupbytag", request.getGroupByTag());
        if (request.getStartTime() != null || request.getEndTime() != null) {
            checkRange(request.getStartTime(), request.getEndTime());
            query.put("startTime", time(request.getStartTime()));
            query.put("endTime", time(request.getEndTime()));
        }
        return get("/api/alarmdata", query, properties.isSecurityEnabled());
    }

    @Override
    public JsonNode querySql(String sql) {
        ObjectNode body = mapper.createObjectNode().put("query", required(sql, "sql"));
        return post("/api/SQL", Map.of(), body, properties.isSecurityEnabled());
    }

    @Override
    public JsonNode listSources() {
        return get("/api/source", Map.of(), true);
    }

    @Override
    public JsonNode getSource(String name) {
        return get(path("/api/source", List.of(required(name, "name"))), Map.of(), true);
    }

    @Override
    public JsonNode addSource(JsonNode body) {
        return post("/api/source/add", Map.of(), object(body), true);
    }

    @Override
    public JsonNode updateSource(JsonNode body) {
        return post("/api/source/update", Map.of(), object(body), true);
    }

    @Override
    public JsonNode deleteSources(List<String> names) {
        return delete("/api/source", query("name", names(names)));
    }

    @Override
    public JsonNode listUsers() {
        return get("/api/user", Map.of(), true);
    }

    @Override
    public JsonNode getUser(String name) {
        return get(path("/api/user", List.of(required(name, "name"))), Map.of(), true);
    }

    @Override
    public JsonNode addUsers(JsonNode body) {
        return post("/api/user/add", Map.of(), array(body), true);
    }

    @Override
    public JsonNode updateUsers(JsonNode body) {
        return post("/api/user/update", Map.of(), array(body), true);
    }

    @Override
    public JsonNode deleteUsers(List<String> names) {
        return delete("/api/user", query("name", names(names)));
    }

    @Override
    public JsonNode listGroups() {
        return get("/api/group", Map.of(), true);
    }

    @Override
    public JsonNode getGroup(String name) {
        return get(path("/api/group", List.of(required(name, "name"))), Map.of(), true);
    }

    @Override
    public JsonNode addGroups(JsonNode body) {
        return post("/api/group/add", Map.of(), array(body), true);
    }

    @Override
    public JsonNode updateGroups(JsonNode body) {
        return post("/api/group/update", Map.of(), array(body), true);
    }

    @Override
    public JsonNode deleteGroups(List<String> names) {
        return delete("/api/group", query("name", names(names)));
    }

    @Override
    public JsonNode listSerials() {
        return get("/api/serial", Map.of(), true);
    }

    @Override
    public JsonNode getSerial(String name) {
        return get(path("/api/serial", List.of(required(name, "name"))), Map.of(), true);
    }

    @Override
    public JsonNode addSerials(JsonNode body) {
        return post("/api/serial/add", Map.of(), array(body), true);
    }

    @Override
    public JsonNode updateSerials(JsonNode body) {
        return post("/api/serial/update", Map.of(), array(body), true);
    }

    @Override
    public JsonNode getSettings() {
        return get("/api/setting", Map.of(), true);
    }

    @Override
    public JsonNode updateSettings(JsonNode body) {
        return post("/api/setting/update", Map.of(), object(body), true);
    }

    @Override
    public JsonNode getIoSettings() {
        return get("/api/iosetting", Map.of(), true);
    }

    @Override
    public JsonNode updateIoSettings(JsonNode body) {
        return post("/api/iosetting/update", Map.of(), object(body), true);
    }

    @Override
    public JsonNode listDrivers() {
        return get("/api/driver", Map.of(), true);
    }

    @Override
    public JsonNode getDriver(String subdir, int subtype) {
        if (subtype < 0) {
            throw new IllegalArgumentException("subtype 不能小于 0");
        }
        Map<String, String> query = query("subdir", required(subdir, "subdir"));
        query.put("subtype", String.valueOf(subtype));
        return get("/api/driver", query, true);
    }

    @Override
    public JsonNode addDevice(JsonNode body) {
        return post("/api/dev/add", Map.of(), object(body), true);
    }

    @Override
    public JsonNode addDevices(JsonNode body) {
        return post("/api/dev/add/batch", Map.of(), array(body), true);
    }

    @Override
    public JsonNode deleteDevices(List<String> names) {
        return delete("/api/dev", query("name", names(names)));
    }

    @Override
    public JsonNode updateDevice(JsonNode body) {
        return post("/api/dev/update", Map.of(), object(body), true);
    }

    @Override
    public JsonNode updateDevices(JsonNode body) {
        return post("/api/dev/update/batch", Map.of(), array(body), true);
    }

    @Override
    public JsonNode getDevice(String name) {
        return get(path("/api/dev", List.of(required(name, "name"))), Map.of(), true);
    }

    @Override
    public JsonNode getDevices(List<String> names) {
        return get("/api/dev", query("names", names(names)), true);
    }

    @Override
    public JsonNode listDevices() {
        return get("/api/dev", Map.of(), true);
    }

    @Override
    public JsonNode getDirectory(List<String> segments, Boolean recurve, Boolean withTag) {
        Map<String, String> query = new LinkedHashMap<>();
        putFlag(query, "recurve", recurve);
        putFlag(query, "withtag", withTag);
        return get(path("/api/org", segments), query, true);
    }

    @Override
    public JsonNode addDirectory(JsonNode body) {
        return post("/api/org/add", Map.of(), object(body), true);
    }

    @Override
    public JsonNode updateDirectory(JsonNode body) {
        return post("/api/org/update", Map.of(), object(body), true);
    }

    @Override
    public JsonNode deleteDirectory(List<String> segments) {
        nonEmpty(segments, "segments");
        return delete(path("/api/org", segments), Map.of());
    }

    @Override
    public JsonNode addTag(JsonNode body) {
        return post("/api/tag/add", Map.of(), object(body), true);
    }

    @Override
    public JsonNode addTags(JsonNode body) {
        return post("/api/tag/add/batch", Map.of(), array(body), true);
    }

    @Override
    public JsonNode updateTag(JsonNode body) {
        return post("/api/tag/update", Map.of(), object(body), true);
    }

    @Override
    public JsonNode updateTags(JsonNode body) {
        return post("/api/tag/update/batch", Map.of(), array(body), true);
    }

    @Override
    public JsonNode deleteTags(List<String> names) {
        return delete("/api/tag", query("name", names(names)));
    }

    @Override
    public JsonNode listTags() {
        return get("/api/tag", Map.of(), true);
    }

    @Override
    public JsonNode getTag(String name) {
        return get(path("/api/tag", List.of(required(name, "name"))), Map.of(), true);
    }

    @Override
    public JsonNode getTags(List<String> names) {
        return get("/api/tag", query("name", names(names)), true);
    }

    @Override
    public JsonNode listExports() {
        return get("/api/export", Map.of(), true);
    }

    @Override
    public JsonNode getExport(String name) {
        return get("/api/export", query("name", required(name, "name")), true);
    }

    @Override
    public JsonNode addExport(String name, JsonNode body) {
        return post("/api/export/add", query("name", required(name, "name")), object(body), true);
    }

    @Override
    public JsonNode updateExport(String name, JsonNode body) {
        String endpoint = properties.getUpdateExportPath().replace("{name}", encode(required(name, "name")));
        return post(endpoint, query("name", name), object(body), true);
    }

    @Override
    public JsonNode deleteExportItems(String name, List<String> variables) {
        Map<String, String> query = query("name", required(name, "name"));
        query.put("vars", names(variables));
        return delete("/api/export", query);
    }

    @Override
    public JsonNode synchronizeExport(String name) {
        return execute("POST", "/api/export/synch", query("name", required(name, "name")), null,
                true, properties.getSyncTimeout());
    }

    // ==================== HTTP 与认证 ====================

    private JsonNode get(String path, Map<String, String> query, boolean authenticated) {
        return execute("GET", path, query, null, authenticated, properties.getReadTimeout());
    }

    private JsonNode post(String path, Map<String, String> query, JsonNode body, boolean authenticated) {
        return execute("POST", path, query, body, authenticated, properties.getReadTimeout());
    }

    private JsonNode delete(String path, Map<String, String> query) {
        return execute("DELETE", path, query, null, true, properties.getReadTimeout());
    }

    private JsonNode execute(String method, String path, Map<String, String> query, JsonNode body,
                             boolean authenticated, Duration timeout) {
        Map<String, String> parameters = new LinkedHashMap<>(query);
        String current = authenticated ? requireToken() : null;
        if (current != null) {
            parameters.put("token", current);
        }
        return exchange(method, path, parameters, body, timeout, current);
    }

    private JsonNode exchange(String method, String path, Map<String, String> query, JsonNode body,
                              Duration timeout, String currentToken) {
        String suffix = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        URI uri = URI.create(baseUrl + path + (suffix.isEmpty() ? "" : "?" + suffix));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json");
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            builder.header("Content-Type", "application/json; charset=UTF-8");
            publisher = HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
        }
        HttpResponse<String> response;
        try {
            // 不在客户端重试。POST、DELETE、注册、登出均可能改变服务端状态。
            response = httpClient.send(builder.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException exception) {
            throw new RealDbException("紫金桥请求超时，操作结果未知，请核实服务端状态", null, null, null, null, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RealDbException("紫金桥请求被中断，操作结果未知", null, null, null, null, true);
        } catch (IOException exception) {
            // 不保留含完整请求 URI 的底层异常，避免查询参数中的密码/token 进入日志。
            throw new RealDbException("紫金桥通信失败，操作结果未知", null, null, null, null, true);
        }
        int status = response.statusCode();
        String raw = response.body();
        JsonNode json = null;
        if (raw != null && !raw.isBlank()) {
            try {
                json = mapper.readTree(raw);
            } catch (JsonProcessingException ignored) {
                // HTTP 错误（例如同步发布项超时）可能不返回 JSON，保留原文供显式处理。
            }
        }
        Integer code = errorCode(json);
        if (status == 401 || status == 403 || isAuthenticationError(code)) {
            invalidateToken(currentToken);
        }
        if (status < 200 || status >= 300) {
            throw new RealDbException("紫金桥 HTTP 请求失败，状态码 " + status, status, code, json, raw,
                    status == 406 && path.equals("/api/export/synch"));
        }
        if (json == null || (!json.isObject() && !json.isArray())) {
            throw new RealDbException("紫金桥返回了无效 JSON 对象或数组", status, null, json, raw, true);
        }
        if (code != null && code != 0) {
            throw new RealDbException("紫金桥业务请求失败，错误码 " + code, status, code, json, raw, false);
        }
        return json;
    }

    private String requireToken() {
        String current = token;
        if (current != null) {
            return current;
        }
        synchronized (tokenLock) {
            if (token == null) {
                token = authenticate();
            }
            return token;
        }
    }

    private String authenticate() {
        if (!hasText(properties.getUsername()) || properties.getPassword() == null) {
            throw new IllegalStateException("此紫金桥操作需要认证，请配置 token 或 username/password");
        }
        JsonNode response = login(properties.getUsername(), properties.getPassword());
        JsonNode received = response.get("token");
        if (received == null || !received.isTextual() || !hasText(received.textValue())) {
            throw new RealDbException("紫金桥登录响应缺少 token", 200, null, response, null, false);
        }
        return received.textValue();
    }

    private void invalidateToken(String expected) {
        synchronized (tokenLock) {
            if (Objects.equals(token, expected)) {
                token = null;
            }
        }
    }

    private static boolean isAuthenticationError(Integer code) {
        return code != null && (code == -1 || code == 21 || code == 36 || code == 37 || code == 403);
    }

    private static Integer errorCode(JsonNode json) {
        if (json == null) {
            return null;
        }
        if (json.isArray()) {
            for (JsonNode item : json) {
                Integer code = errorCode(item);
                if (code != null && code != 0) {
                    return code;
                }
            }
            return null;
        }
        JsonNode value = json.get("code");
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Integer.valueOf(value.asText());
        } catch (NumberFormatException exception) {
            throw new RealDbException("紫金桥返回了无效错误码", null, null, json, null, false);
        }
    }

    // ==================== 协议参数 ====================

    private static Map<String, String> query(String name, String value) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(name, value);
        return query;
    }

    private static Map<String, String> options(RealDbReadOptionsDTO options) {
        Map<String, String> query = new LinkedHashMap<>();
        if (options != null) {
            putDecimal(query, options.getDecimal());
            putFlag(query, "valueonly", options.getValueOnly());
        }
        return query;
    }

    private static void put(Map<String, String> query, String key, Object value) {
        if (value != null) {
            query.put(key, String.valueOf(value));
        }
    }

    private static void putFlag(Map<String, String> query, String key, Boolean value) {
        if (value != null) {
            query.put(key, value ? "1" : "0");
        }
    }

    private static void putDecimal(Map<String, String> query, Integer decimal) {
        if (decimal != null && (decimal < 0 || decimal > 9)) {
            throw new IllegalArgumentException("decimal 必须在 0 至 9 之间");
        }
        put(query, "decimal", decimal);
    }

    private static String names(List<String> values) {
        nonEmpty(values, "names");
        for (String value : values) {
            if (required(value, "name").contains(",")) {
                throw new IllegalArgumentException("名称不能包含协议分隔符逗号");
            }
        }
        return String.join(",", values);
    }

    private static String path(String prefix, List<String> segments) {
        Objects.requireNonNull(segments, "segments");
        StringBuilder result = new StringBuilder(prefix);
        for (String segment : segments) {
            required(segment, "path segment");
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("目录段不能为 . 或 ..");
            }
            result.append('/').append(encode(segment));
        }
        return result.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String time(LocalDateTime value) {
        Objects.requireNonNull(value, "time");
        if (value.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("时间最多支持毫秒精度");
        }
        return TIME_FORMAT.format(value);
    }

    private static void checkRange(LocalDateTime startTime, LocalDateTime endTime) {
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
        if (startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }
    }

    private static String required(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void nonEmpty(List<?> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static JsonNode object(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体必须为 JSON 对象");
        }
        return body;
    }

    private static JsonNode array(JsonNode body) {
        if (body == null || !body.isArray() || body.isEmpty()) {
            throw new IllegalArgumentException("请求体必须为非空 JSON 对象数组");
        }
        for (JsonNode item : body) {
            object(item);
        }
        return body;
    }

    private JsonNode scalar(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw new IllegalArgumentException("点值必须为数字或字符串");
        }
        JsonNode node = mapper.valueToTree(value);
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException("点值不能为 NaN 或 Infinity");
        }
        return node;
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }

    private static String validateBaseUrl(String value) {
        required(value, "baseUrl");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("baseUrl 必须为合法 HTTP(S) 地址");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl 必须为不含用户信息、查询参数或片段的 HTTP(S) 地址");
        }
        return value.replaceAll("/+$", "");
    }

}
