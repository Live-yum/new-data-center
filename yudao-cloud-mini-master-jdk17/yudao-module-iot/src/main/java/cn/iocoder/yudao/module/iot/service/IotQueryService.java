package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbHistoryQueryDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbReadOptionsDTO;
import cn.iocoder.yudao.module.iot.model.IotModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Read-only, tenant-resolved adapter. No external point names, SQL or URLs are accepted. */
@Service
public class IotQueryService {
    private static final int MAX_SAMPLES = 10_000;
    private static final Set<String> AGGREGATIONS = Set.of("RAW", "AVG", "MIN", "MAX", "LAST");
    private final IotRepository repository;
    private final ObjectProvider<RealDbClient> clients;
    private final ZoneId zone;

    public IotQueryService(IotRepository repository, ObjectProvider<RealDbClient> clients,
                           @Value("${yudao.iot.realdb-zone:UTC}") String zone) {
        this.repository = repository;
        this.clients = clients;
        this.zone = ZoneId.of(zone);
    }

    public QueryResult realtime(long tenant, Query query) {
        validate(query, false);
        List<Binding> bindings = bindings(tenant, query);
        List<String> names = bindings.stream().map(b -> b.mapping.pointName()).distinct().toList();
        JsonNode raw = client().getNamedData(names, new RealDbReadOptionsDTO().setValueOnly(false));
        Map<String, JsonNode> items = indexed(raw, names);
        String receivedAt = Instant.now().toString();
        List<Series> series = new ArrayList<>();
        for (Binding b : bindings) {
            JsonNode item = items.get(b.mapping.pointName());
            Point point = item == null ? new Point(receivedAt, null, "MISSING", "RECEIVED") : point(item, receivedAt);
            series.add(new Series(b.deviceId, b.mapping.metricKey(), b.mapping.unit(), List.of(point)));
        }
        return new QueryResult("REALDB", series);
    }

    public QueryResult history(long tenant, Query query) {
        validate(query, true);
        List<Binding> bindings = bindings(tenant, query);
        List<String> names = bindings.stream().map(b -> b.mapping.pointName()).distinct().toList();
        // A local-time-only vendor API cannot represent a DST overlap unambiguously.
        LocalDateTime start = local(query.startTime());
        LocalDateTime end = local(query.endTime());
        if (!zone.getRules().getOffset(query.startTime().toInstant())
                .equals(zone.getRules().getOffset(query.endTime().toInstant()))) {
            throw invalid("查询窗口跨越实时库时区夏令时切换，请拆分查询");
        }
        JsonNode raw = client().getHistoryRange(new RealDbHistoryQueryDTO().setNames(names)
                .setStartTime(start).setEndTime(end).setInterval(query.intervalMs())
                .setOptions(new RealDbReadOptionsDTO().setValueOnly(false)));
        Map<String, JsonNode> items = indexed(raw, names);
        List<Series> result = new ArrayList<>();
        int count = 0;
        for (Binding b : bindings) {
            List<Point> points = new ArrayList<>();
            JsonNode item = items.get(b.mapping.pointName());
            if (item != null) {
                JsonNode values = item.get("vals");
                if (values == null || !values.isArray()) throw unsupported();
                for (JsonNode value : values) {
                    if (++count > MAX_SAMPLES) throw invalid("实时库响应超过最大点数");
                    Point p = point(value, null);
                    Instant time = Instant.parse(p.time());
                    if (time.isBefore(query.startTime().toInstant()) || time.isAfter(query.endTime().toInstant())) {
                        throw invalid("实时库响应时间超出请求范围");
                    }
                    points.add(p);
                }
            }
            points.sort(Comparator.comparing(p -> Instant.parse(p.time())));
            result.add(new Series(b.deviceId, b.mapping.metricKey(), b.mapping.unit(), aggregate(points, query.aggregation())));
        }
        return new QueryResult("REALDB", result);
    }

    public static void validate(Query q, boolean history) {
        if (q == null || q.deviceIds() == null || q.metricKeys() == null
                || q.deviceIds().isEmpty() || q.deviceIds().size() > 10
                || q.metricKeys().isEmpty() || q.metricKeys().size() > 20
                || q.deviceIds().size() * q.metricKeys().size() > 50
                || q.deviceIds().stream().anyMatch(id -> id == null || id <= 0)
                || q.metricKeys().stream().anyMatch(k -> k == null || !k.matches("[A-Za-z0-9_.-]{1,64}"))
                || new HashSet<>(q.deviceIds()).size() != q.deviceIds().size()
                || new HashSet<>(q.metricKeys()).size() != q.metricKeys().size()
                || q.aggregation() == null || !AGGREGATIONS.contains(q.aggregation())) {
            throw invalid("设备、指标或聚合参数无效（最多10设备、20指标、50序列）");
        }
        if (!history) {
            if (!"RAW".equals(q.aggregation())) throw invalid("实时查询仅支持RAW");
            return;
        }
        if (q.startTime() == null || q.endTime() == null || q.intervalMs() == null || q.intervalMs() < 1000) {
            throw invalid("历史查询必须提供带时区的起止时间和至少1000ms采样间隔");
        }
        Duration duration = Duration.between(q.startTime().toInstant(), q.endTime().toInstant());
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofDays(7)) > 0) {
            throw invalid("查询窗口须大于0且不超过7天");
        }
        long samples = duration.toMillis() / q.intervalMs() + 1;
        if (samples * q.deviceIds().size() * q.metricKeys().size() > MAX_SAMPLES) {
            throw invalid("查询超过10000采样点，请增大间隔或缩小范围");
        }
    }

    private List<Binding> bindings(long tenant, Query q) {
        if (tenant <= 0) throw invalid("租户上下文缺失");
        List<Binding> result = new ArrayList<>();
        for (Long device : q.deviceIds()) {
            Map<String, Mapping> mappings = new HashMap<>();
            for (Mapping mapping : repository.mappings(tenant, device)) mappings.put(mapping.metricKey(), mapping);
            for (String metric : q.metricKeys()) {
                Mapping mapping = mappings.get(metric);
                if (mapping == null) throw invalid("设备未配置请求的统一指标映射");
                result.add(new Binding(device, mapping));
            }
        }
        return result;
    }

    private RealDbClient client() {
        RealDbClient client = clients.getIfAvailable();
        if (client == null) throw new ServiceException(1_070_000_002, "真实数据源未启用，请配置RealDB；不会回退演示数据");
        return client;
    }

    private static Map<String, JsonNode> indexed(JsonNode raw, List<String> requested) {
        if (raw == null || !raw.isObject() || !raw.path("items").isArray()) throw unsupported();
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode item : raw.path("items")) {
            if (!item.isObject() || !item.path("name").isTextual()) throw unsupported();
            String name = item.path("name").textValue();
            if (!requested.contains(name) || result.putIfAbsent(name, item) != null) throw unsupported();
        }
        return result;
    }

    private Point point(JsonNode item, String fallbackTime) {
        if (!item.isObject() || !item.has("val")) throw unsupported();
        JsonNode val = item.get("val");
        if (!val.isNull() && !val.isNumber() && !val.isTextual()) throw unsupported();
        Object value = val.isNull() ? null : val.isNumber() ? val.decimalValue() : val.textValue();
        String quality = value == null ? "MISSING" : quality(item.get("quality"));
        String time;
        if (item.hasNonNull("time")) {
            if (!item.get("time").isTextual()) throw unsupported();
            time = instant(item.get("time").textValue()).toString();
        } else {
            if (fallbackTime == null) throw unsupported();
            time = fallbackTime;
        }
        return new Point(time, value, quality, item.hasNonNull("time") ? "DEVICE" : "RECEIVED");
    }

    private Instant instant(String time) {
        try {
            return OffsetDateTime.parse(time).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                LocalDateTime local = LocalDateTime.parse(time);
                List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
                if (offsets.size() != 1) throw invalid("实时库时间存在夏令时歧义");
                return local.toInstant(offsets.get(0));
            } catch (DateTimeParseException invalid) {
                throw unsupported();
            }
        }
    }

    private LocalDateTime local(OffsetDateTime time) {
        LocalDateTime local = LocalDateTime.ofInstant(time.toInstant(), zone);
        if (zone.getRules().getValidOffsets(local).size() != 1) throw invalid("实时库时间存在夏令时歧义");
        return local;
    }

    private static String quality(JsonNode value) {
        if (value == null || !value.isTextual()) return "UNKNOWN";
        String quality = value.textValue().toUpperCase(Locale.ROOT);
        return Set.of("GOOD", "BAD", "UNCERTAIN").contains(quality) ? quality : "UNKNOWN";
    }

    static List<Point> aggregate(List<Point> points, String aggregation) {
        if (points.isEmpty() || "RAW".equals(aggregation)) return List.copyOf(points);
        if ("LAST".equals(aggregation)) return List.of(points.get(points.size() - 1));
        List<Point> eligible = points.stream().filter(p -> p.value() != null && !"BAD".equals(p.quality())).toList();
        String time = points.get(points.size() - 1).time();
        if (eligible.isEmpty()) return List.of(new Point(time, null, "MISSING"));
        BigDecimal sum = BigDecimal.ZERO, min = null, max = null;
        for (Point point : eligible) {
            if (!(point.value() instanceof Number)) throw invalid("数值聚合不支持文本指标");
            BigDecimal value = new BigDecimal(point.value().toString());
            sum = sum.add(value);
            min = min == null || value.compareTo(min) < 0 ? value : min;
            max = max == null || value.compareTo(max) > 0 ? value : max;
        }
        Object value = switch (aggregation) {
            case "AVG" -> sum.divide(BigDecimal.valueOf(eligible.size()), MathContext.DECIMAL64);
            case "MIN" -> min;
            case "MAX" -> max;
            default -> throw invalid("不支持的聚合");
        };
        // An incomplete or unknown-quality input must never become a fabricated GOOD aggregate.
        String quality = points.stream().anyMatch(p -> "BAD".equals(p.quality()) || "MISSING".equals(p.quality()) || "UNCERTAIN".equals(p.quality()))
                ? "UNCERTAIN" : points.stream().anyMatch(p -> "UNKNOWN".equals(p.quality())) ? "UNKNOWN" : "GOOD";
        return List.of(new Point(time, value, quality));
    }

    private static ServiceException invalid(String message) { return new ServiceException(1_070_000_001, message); }
    private static ServiceException unsupported() { return new ServiceException(1_070_000_003, "实时库返回格式未适配，拒绝猜测数据；请核对实际响应契约"); }
    private record Binding(Long deviceId, Mapping mapping) { }
}
