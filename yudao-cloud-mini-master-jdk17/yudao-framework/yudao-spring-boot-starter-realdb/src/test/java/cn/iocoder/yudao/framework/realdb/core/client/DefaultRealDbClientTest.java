package cn.iocoder.yudao.framework.realdb.core.client;

import cn.iocoder.yudao.framework.realdb.config.RealDbProperties;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbAlarmQueryDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbHistoryQueryDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbHistoryValueDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbReadOptionsDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbValueDTO;
import cn.iocoder.yudao.framework.realdb.core.exception.RealDbException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 使用本机 HTTP 服务验证真实线缆协议。66 个预期对应 PDF 各节，而非反射客户端内部常量。
 */
class DefaultRealDbClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final LocalDateTime START = LocalDateTime.of(2026, 5, 28, 10, 0, 0, 123_000_000);
    private static final LocalDateTime END = START.plusSeconds(1);
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final AtomicInteger loginCount = new AtomicInteger();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private RealDbProperties properties;
    private DefaultRealDbClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI(), body,
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            boolean login = exchange.getRequestURI().getPath().equals("/api/userlogin");
            if (login) {
                loginCount.incrementAndGet();
            }
            Reply reply = replies.poll();
            if (reply == null) {
                reply = new Reply(200, login ? "{\"code\":0,\"token\":\"session-token\"}" : "{\"code\":0}", 0);
            }
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            try {
                exchange.sendResponseHeaders(reply.status(), response.length == 0 ? -1 : response.length);
                if (response.length > 0) {
                    exchange.getResponseBody().write(response);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        properties = new RealDbProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setToken("token+中文&");
        client = new DefaultRealDbClient(properties, MAPPER);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @TestFactory
    Stream<DynamicTest> all66DocumentedOperationsUseTheirWireContract() {
        JsonNode config = json("{\"NAME\":\"演示\",\"CUSTOMPARS\":{\"#value\":17,\"CaseSensitive\":\"保留\"}}");
        JsonNode configs = MAPPER.createArrayNode().add(config);
        RealDbReadOptionsDTO options = new RealDbReadOptionsDTO().setDecimal(2).setValueOnly(true).setRecursion(true);
        List<Contract> cases = new ArrayList<>();
        cases.add(contract("3.1", c -> c.login("用户 +", "密&码"), "GET", "/api/userlogin", q("user", "用户 +", "pass", "密&码"), null));
        cases.add(contract("3.2", RealDbClient::logout, "GET", "/api/userlogout", q(), null));
        cases.add(contract("4.1", c -> c.registerTags("registered", List.of("A.PV", "B.DESC")), "GET", "/api/realdata", q("regname", "registered", "names", "A.PV,B.DESC"), null));
        cases.add(contract("4.2", c -> c.getRegisteredData("registered", 2), "GET", "/api/realdata", q("regname", "registered", "decimal", "2"), null));
        cases.add(contract("4.3", c -> c.getRealDirectory(List.of("root", "group")), "GET", "/api/realdir/root/group", q(), null));
        cases.add(contract("4.4", c -> c.getNodeData(List.of("root", "group"), options), "GET", "/api/realdata/root/group", q("decimal", "2", "valueonly", "1", "recursion", "1"), null));
        cases.add(contract("4.5", c -> c.getTagData(List.of("A", "B"), List.of("PV", "DESC"), options), "GET", "/api/realdata", q("tags", "A,B", "pars", "PV,DESC", "decimal", "2", "valueonly", "1"), null));
        cases.add(contract("4.6", c -> c.getNamedData(List.of("A.PV", "B.DESC"), options), "GET", "/api/realdata", q("names", "A.PV,B.DESC", "decimal", "2", "valueonly", "1"), null));
        cases.add(contract("4.7", c -> c.setRealData(List.of(new RealDbValueDTO().setName("A.PV").setValue(1.25), new RealDbValueDTO().setName("B.DESC").setValue("温度"))), "POST", "/api/realdata", q(), json("{\"items\":[{\"name\":\"A.PV\",\"val\":1.25},{\"name\":\"B.DESC\",\"val\":\"温度\"}]}")));
        cases.add(contract("4.8", c -> c.setRealDataArrays(List.of("A.PV", "B.DESC"), List.of(12, "温度")), "POST", "/api/realdata", q(), json("{\"names\":[\"A.PV\",\"B.DESC\"],\"vals\":[12,\"温度\"]}")));
        cases.add(contract("4.9", c -> c.getHistoryAtTimes(List.of("A.PV"), List.of(START, END), options), "GET", "/api/hisdata", q("names", "A.PV", "times", "2026-05-28T10:00:00.123,2026-05-28T10:00:01.123", "decimal", "2", "valueonly", "1"), null));
        cases.add(contract("4.10", c -> c.getHistoryRange(new RealDbHistoryQueryDTO().setNames(List.of("A.PV")).setStartTime(START).setEndTime(END).setInterval(250L).setOptions(options)), "GET", "/api/hisdata", q("names", "A.PV", "startTime", "2026-05-28T10:00:00.123", "endTime", "2026-05-28T10:00:01.123", "interval", "250", "decimal", "2", "valueonly", "1"), null));
        cases.add(contract("4.11", c -> c.setHistory(List.of(new RealDbHistoryValueDTO().setName("A.PV").setValues(List.of(new RealDbHistoryValueDTO.Value().setTime(START).setValue(3))))), "POST", "/api/hisdata", q(), json("{\"items\":[{\"name\":\"A.PV\",\"vals\":[{\"time\":\"2026-05-28T10:00:00.123\",\"val\":3}]}]}")));
        cases.add(contract("4.12", c -> c.getAlarms(new RealDbAlarmQueryDTO().setRealAlarmCount(9).setOrg("root").setUnit(0).setLevel(1).setTags(List.of("A", "B")).setGroupByTag(true).setStartTime(START).setEndTime(END)), "GET", "/api/alarmdata", q("realalmcount", "9", "org", "root", "unit", "0", "level", "1", "tags", "A,B", "groupbytag", "1", "startTime", "2026-05-28T10:00:00.123", "endTime", "2026-05-28T10:00:01.123"), null));
        cases.add(contract("4.13", c -> c.querySql("select * from RealData where kind<100"), "POST", "/api/SQL", q(), json("{\"query\":\"select * from RealData where kind<100\"}")));
        cases.add(contract("5.1.1", RealDbClient::listSources, "GET", "/api/source", q(), null));
        cases.add(contract("5.1.2", c -> c.getSource("remote1"), "GET", "/api/source/remote1", q(), null));
        cases.add(contract("5.1.3", c -> c.addSource(config), "POST", "/api/source/add", q(), config));
        cases.add(contract("5.1.4", c -> c.updateSource(config), "POST", "/api/source/update", q(), config));
        cases.add(contract("5.1.5", c -> c.deleteSources(List.of("r1", "r2")), "DELETE", "/api/source", q("name", "r1,r2"), null));
        cases.add(contract("5.2.1", RealDbClient::listUsers, "GET", "/api/user", q(), null));
        cases.add(contract("5.2.2", c -> c.getUser("user1"), "GET", "/api/user/user1", q(), null));
        cases.add(contract("5.2.3", c -> c.addUsers(configs), "POST", "/api/user/add", q(), configs));
        cases.add(contract("5.2.4", c -> c.updateUsers(configs), "POST", "/api/user/update", q(), configs));
        cases.add(contract("5.2.5", c -> c.deleteUsers(List.of("u1", "u2")), "DELETE", "/api/user", q("name", "u1,u2"), null));
        cases.add(contract("5.3.1", RealDbClient::listGroups, "GET", "/api/group", q(), null));
        cases.add(contract("5.3.2", c -> c.getGroup("group1"), "GET", "/api/group/group1", q(), null));
        cases.add(contract("5.3.3", c -> c.addGroups(configs), "POST", "/api/group/add", q(), configs));
        cases.add(contract("5.3.4", c -> c.updateGroups(configs), "POST", "/api/group/update", q(), configs));
        cases.add(contract("5.3.5", c -> c.deleteGroups(List.of("g1", "g2")), "DELETE", "/api/group", q("name", "g1,g2"), null));
        cases.add(contract("5.4.1", RealDbClient::listSerials, "GET", "/api/serial", q(), null));
        cases.add(contract("5.4.2", c -> c.getSerial("COM1"), "GET", "/api/serial/COM1", q(), null));
        cases.add(contract("5.4.3", c -> c.addSerials(configs), "POST", "/api/serial/add", q(), configs));
        cases.add(contract("5.4.4", c -> c.updateSerials(configs), "POST", "/api/serial/update", q(), configs));
        cases.add(contract("5.5.1", RealDbClient::getSettings, "GET", "/api/setting", q(), null));
        cases.add(contract("5.5.2", c -> c.updateSettings(config), "POST", "/api/setting/update", q(), config));
        cases.add(contract("5.6.1", RealDbClient::getIoSettings, "GET", "/api/iosetting", q(), null));
        cases.add(contract("5.6.2", c -> c.updateIoSettings(config), "POST", "/api/iosetting/update", q(), config));
        cases.add(contract("5.7.1", RealDbClient::listDrivers, "GET", "/api/driver", q(), null));
        cases.add(contract("5.7.2", c -> c.getDriver("ModBus", 1), "GET", "/api/driver", q("subdir", "ModBus", "subtype", "1"), null));
        cases.add(contract("5.8.1", c -> c.addDevice(config), "POST", "/api/dev/add", q(), config));
        cases.add(contract("5.8.2", c -> c.addDevices(configs), "POST", "/api/dev/add/batch", q(), configs));
        cases.add(contract("5.8.3", c -> c.deleteDevices(List.of("d1", "d2")), "DELETE", "/api/dev", q("name", "d1,d2"), null));
        cases.add(contract("5.8.4", c -> c.updateDevice(config), "POST", "/api/dev/update", q(), config));
        cases.add(contract("5.8.5", c -> c.updateDevices(configs), "POST", "/api/dev/update/batch", q(), configs));
        cases.add(contract("5.8.6", c -> c.getDevice("d1"), "GET", "/api/dev/d1", q(), null));
        cases.add(contract("5.8.7", c -> c.getDevices(List.of("d1", "d2")), "GET", "/api/dev", q("names", "d1,d2"), null));
        cases.add(contract("5.8.8", RealDbClient::listDevices, "GET", "/api/dev", q(), null));
        cases.add(contract("5.9.1", c -> c.getDirectory(List.of("root", "factory"), true, false), "GET", "/api/org/root/factory", q("recurve", "1", "withtag", "0"), null));
        cases.add(contract("5.9.2", c -> c.addDirectory(config), "POST", "/api/org/add", q(), config));
        cases.add(contract("5.9.3", c -> c.updateDirectory(config), "POST", "/api/org/update", q(), config));
        cases.add(contract("5.9.4", c -> c.deleteDirectory(List.of("root", "factory")), "DELETE", "/api/org/root/factory", q(), null));
        cases.add(contract("5.10.1", c -> c.addTag(config), "POST", "/api/tag/add", q(), config));
        cases.add(contract("5.10.2", c -> c.addTags(configs), "POST", "/api/tag/add/batch", q(), configs));
        cases.add(contract("5.10.3", c -> c.updateTag(config), "POST", "/api/tag/update", q(), config));
        cases.add(contract("5.10.4", c -> c.updateTags(configs), "POST", "/api/tag/update/batch", q(), configs));
        cases.add(contract("5.10.5", c -> c.deleteTags(List.of("t1", "t2")), "DELETE", "/api/tag", q("name", "t1,t2"), null));
        cases.add(contract("5.10.6", RealDbClient::listTags, "GET", "/api/tag", q(), null));
        cases.add(contract("5.10.7", c -> c.getTag("t1"), "GET", "/api/tag/t1", q(), null));
        cases.add(contract("5.10.8", c -> c.getTags(List.of("t1", "t2")), "GET", "/api/tag", q("name", "t1,t2"), null));
        cases.add(contract("5.11.1", RealDbClient::listExports, "GET", "/api/export", q(), null));
        cases.add(contract("5.11.2", c -> c.getExport("opcua"), "GET", "/api/export", q("name", "opcua"), null));
        cases.add(contract("5.11.3", c -> c.addExport("opcua", config), "POST", "/api/export/add", q("name", "opcua"), config));
        cases.add(contract("5.11.4", c -> c.updateExport("opcua", config), "POST", "/api/export", q("name", "opcua"), config));
        cases.add(contract("5.11.5", c -> c.deleteExportItems("opcua", List.of("a1.pv", "a2.pv")), "DELETE", "/api/export", q("name", "opcua", "vars", "a1.pv,a2.pv"), null));
        cases.add(contract("5.11.6", c -> c.synchronizeExport("opcua"), "POST", "/api/export/synch", q("name", "opcua"), null));
        assertThat(cases).hasSize(66);
        return cases.stream().map(contract -> DynamicTest.dynamicTest(contract.section(), () -> {
            // DynamicTest 共用生命周期；显式重置会话，避免登出影响后续契约。
            client = new DefaultRealDbClient(properties, MAPPER);
            requests.clear();
            contract.call().accept(client);
            Request actual = take();
            assertThat(actual.method()).isEqualTo(contract.method());
            assertThat(actual.uri().getRawPath()).isEqualTo(contract.path());
            Map<String, String> expected = new LinkedHashMap<>(contract.query());
            if (!contract.section().equals("3.1")) {
                expected.put("token", "token+中文&");
            }
            assertThat(decodeQuery(actual.uri())).isEqualTo(expected);
            if (contract.body() == null) {
                assertThat(actual.body()).isEmpty();
            } else {
                assertThat(json(actual.body())).isEqualTo(contract.body());
                assertThat(actual.contentType()).isEqualTo("application/json; charset=UTF-8");
            }
            assertThat(requests).isEmpty();
        }));
    }

    @Test
    void preservesArraysOptionalCodeColumnCaseAndScalarTypes() {
        for (String raw : List.of("[\"COM1\",\"COM2\"]", "{\"items\":[{\"name\":\"中文\"}]}" ,
                "{\"code\":0,\"DateTime\":[\"2026-05-28T10:00:00\"],\"Value\":[1.5,\"文本\"],\"#value\":4}")) {
            replies.add(new Reply(200, raw, 0));
            assertThat(client.listSerials()).isEqualTo(json(raw));
        }
    }

    @Test
    void preservesPartialBatchErrorsAndNumericStringCodes() {
        String response = "[{\"code\":0,\"name\":\"COM1\"},{\"code\":\"4\",\"name\":\"COM2\"}]";
        replies.add(new Reply(200, response, 0));
        RealDbException error = assertThrows(RealDbException.class,
                () -> client.addSerials(json("[{\"NAME\":\"COM1\"},{\"NAME\":\"COM2\"}]")));
        assertThat(error.getCode()).isEqualTo(4);
        assertThat(error.getHttpStatus()).isEqualTo(200);
        assertThat(error.getResponse()).isEqualTo(json(response));
        assertThat(error.getMessage()).doesNotContain("COM1", "COM2", "token");
    }

    @Test
    void preservesDecimalPrecisionInVendorValues() {
        replies.add(new Reply(200, "{\"code\":0,\"items\":[0.1234567890123456789]}", 0));
        assertThat(client.getNamedData(List.of("A.PV"), null).path("items").get(0).decimalValue())
                .isEqualByComparingTo("0.1234567890123456789");
    }

    @Test
    void encodesEachPathSegmentAndQueryValueWithoutAllowingQueryInjection() throws Exception {
        client.getSource("中 文/源?&+#");
        Request request = take();
        assertThat(request.uri().getRawPath()).isEqualTo("/api/source/%E4%B8%AD%20%E6%96%87%2F%E6%BA%90%3F%26%2B%23");
        assertThat(decodeQuery(request.uri())).containsExactlyEntriesOf(q("token", "token+中文&"));
        assertThat(request.uri().getRawQuery()).contains("%2B", "%26");
    }

    @Test
    void dataReadsCanSkipAuthenticationButRegistrationAndConfigurationCannot() throws Exception {
        properties.setSecurityEnabled(false);
        properties.setToken(null);
        client = new DefaultRealDbClient(properties, MAPPER);
        client.getNamedData(List.of("A.PV"), null);
        assertThat(decodeQuery(take().uri())).doesNotContainKey("token");
        assertThatThrownBy(() -> client.registerTags("a", List.of("A.PV"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(client::listUsers).isInstanceOf(IllegalStateException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void concurrentFirstUseAuthenticatesOnlyOnce() throws Exception {
        credentials();
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Callable<JsonNode>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                calls.add(client::listTags);
            }
            for (var result : callers.invokeAll(calls)) {
                assertThat(result.get().path("code").asInt()).isZero();
            }
            assertThat(loginCount).hasValue(1);
            assertThat(requests).hasSize(17);
            for (Request request : requests) {
                if (!request.uri().getPath().equals("/api/userlogin")) {
                    assertThat(decodeQuery(request.uri())).containsEntry("token", "session-token");
                }
            }
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void invalidTokenIsClearedButFailedMutationIsNeverReplayed() throws Exception {
        properties.setUsername("service");
        properties.setPassword("password");
        client = new DefaultRealDbClient(properties, MAPPER);
        replies.add(new Reply(200, "{\"code\":403}", 0));
        assertThatThrownBy(() -> client.addTag(json("{\"Name\":\"A\"}"))).isInstanceOf(RealDbException.class);
        assertThat(requests).hasSize(1);
        assertThat(loginCount).hasValue(0);
        client.listTags(); // 下一次显式调用才登录，不重试失败的 addTag。
        assertThat(loginCount).hasValue(1);
        assertThat(requests.stream().filter(r -> r.uri().getPath().equals("/api/tag/add"))).hasSize(1);
        assertThat(requests).hasSize(3);
    }

    @Test
    void logoutClearsCachedTokenAndExplicitRefreshUsesCredentials() {
        credentials();
        client.listTags();
        client.logout();
        client.listTags();
        assertThat(loginCount).hasValue(2);
        client.refreshToken();
        assertThat(loginCount).hasValue(3);
        assertThat(requests.stream().filter(r -> r.uri().getPath().equals("/api/userlogout"))).hasSize(1);
    }

    @Test
    void explicitLoginDoesNotReplaceTheConfiguredServiceAccountSession() throws Exception {
        assertThat(client.login("another-user", "another-password").get("token").asText()).isEqualTo("session-token");
        take();
        client.listTags();
        assertThat(decodeQuery(take().uri())).containsEntry("token", "token+中文&");
    }

    @Test
    void malformedSuccessAndMissingLoginTokenFailExplicitly() {
        replies.add(new Reply(200, "not-json", 0));
        RealDbException malformed = assertThrows(RealDbException.class, client::listTags);
        assertThat(malformed.getRawResponse()).isEqualTo("not-json");
        credentials();
        replies.add(new Reply(200, "{\"code\":0}", 0));
        RealDbException missingToken = assertThrows(RealDbException.class, client::listTags);
        assertThat(missingToken.getMessage()).contains("缺少 token");
        assertThat(loginCount).hasValue(1);
        assertThat(requests).hasSize(2);
    }

    @Test
    void synchronousExportSupportsLongerTimeoutAndEmptyHttp406WithoutRetry() {
        properties.setReadTimeout(Duration.ofMillis(40));
        properties.setSyncTimeout(Duration.ofSeconds(2));
        client = new DefaultRealDbClient(properties, MAPPER);
        replies.add(new Reply(200, "{\"code\":0,\"MODCOUNT\":123}", 150));
        assertThat(client.synchronizeExport("opcua").get("MODCOUNT").asInt()).isEqualTo(123);
        replies.add(new Reply(406, "", 0));
        RealDbException error = assertThrows(RealDbException.class, () -> client.synchronizeExport("opcua"));
        assertThat(error.getHttpStatus()).isEqualTo(406);
        assertThat(error.isOutcomeUnknown()).isTrue();
        assertThat(error.getResponse()).isNull();
        assertThat(requests).hasSize(2);
    }

    @Test
    void mutationTimeoutIsReportedWithoutRetryOrSecretLeak() {
        properties.setReadTimeout(Duration.ofMillis(60));
        client = new DefaultRealDbClient(properties, MAPPER);
        replies.add(new Reply(200, "{\"code\":0}", 300));
        RealDbException error = assertThrows(RealDbException.class,
                () -> client.updateTag(json("{\"Name\":\"A\"}")));
        assertThat(error.isOutcomeUnknown()).isTrue();
        assertThat(error.getCause()).isNull();
        assertThat(error.toString()).doesNotContain("token+", "http://");
        assertThat(requests).hasSize(1);
    }

    @Test
    void nonJsonHttpErrorPreservesStatusAndBodyAndDoesNotFollowRedirects() {
        replies.add(new Reply(502, "upstream unavailable", 0));
        RealDbException error = assertThrows(RealDbException.class, client::listTags);
        assertThat(error.getHttpStatus()).isEqualTo(502);
        assertThat(error.getRawResponse()).isEqualTo("upstream unavailable");
        replies.add(new Reply(302, "", 0));
        assertThat(assertThrows(RealDbException.class, client::listTags).getHttpStatus()).isEqualTo(302);
        assertThat(requests).hasSize(2);
    }

    @Test
    void rejectsInvalidInputsBeforeAnyNetworkCall() {
        assertThatThrownBy(() -> client.setRealDataArrays(List.of("A.PV"), List.of(1, 2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getNamedData(List.of("A.PV"), new RealDbReadOptionsDTO().setDecimal(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getHistoryRange(new RealDbHistoryQueryDTO().setNames(List.of("A.PV")).setStartTime(END).setEndTime(START).setInterval(1L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getHistoryRange(new RealDbHistoryQueryDTO().setNames(List.of("A.PV")).setStartTime(START).setEndTime(END).setInterval(0L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.addTags(json("{}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.addTag(json("[]"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.deleteDirectory(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getRealDirectory(List.of(".."))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getNamedData(List.of("A,B"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void exportUpdateEndpointCanBeAdaptedWithoutChangingThePublicApi() throws Exception {
        properties.setUpdateExportPath("/api/export/name={name}");
        client = new DefaultRealDbClient(properties, MAPPER);
        client.updateExport("opcua", json("{\"path\":\"\"}"));
        Request request = take();
        assertThat(request.uri().getRawPath()).isEqualTo("/api/export/name=opcua");
        assertThat(decodeQuery(request.uri())).containsEntry("name", "opcua");
        properties.setUpdateExportPath("https://elsewhere.example/api");
        assertThatThrownBy(() -> new DefaultRealDbClient(properties, MAPPER)).isInstanceOf(IllegalArgumentException.class);
    }

    private void credentials() {
        properties.setToken(null);
        properties.setUsername("service");
        properties.setPassword("p&a+ss");
        client = new DefaultRealDbClient(properties, MAPPER);
    }

    private Request take() throws InterruptedException {
        Request result = requests.poll(2, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        return result;
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static Map<String, String> q(String... values) {
        Map<String, String> query = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            query.put(values[i], values[i + 1]);
        }
        return query;
    }

    private static Map<String, String> decodeQuery(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static Contract contract(String section, Consumer<RealDbClient> call, String method,
                                     String path, Map<String, String> query, JsonNode body) {
        return new Contract(section, call, method, path, query, body);
    }

    private record Contract(String section, Consumer<RealDbClient> call, String method, String path,
                            Map<String, String> query, JsonNode body) { }

    private record Request(String method, URI uri, String body, String contentType) { }

    private record Reply(int status, String body, long delayMillis) { }

}
