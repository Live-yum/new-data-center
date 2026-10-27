package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbHistoryQueryDTO;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbReadOptionsDTO;
import cn.iocoder.yudao.module.iot.model.IotModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** Read-only, tenant-resolved adapter. No external point names, SQL or URLs are accepted. */
@Service
public class IotQueryService {
    private static final int MAX_SAMPLES = 10_000;
    private static final Set<String> AGGREGATIONS = Set.of("RAW", "AVG", "MIN", "MAX", "LAST");
    private final IotRepository repository;
    private final IotSourceRegistry sources;

    public IotQueryService(IotRepository repository, IotSourceRegistry sources) {
        this.repository = repository;
        this.sources = sources;
    }

    public QueryResult realtime(long tenant, Query query) {
        validate(query, false);
        try { return execute(tenant, query, bindings(tenant, query), false, false); }
        catch (SourceFailureException failure) { throw failure.asServiceException(); }
    }

    public QueryResult history(long tenant, Query query) {
        validate(query, true);
        try { return execute(tenant, query, bindings(tenant, query), true, false); }
        catch (SourceFailureException failure) { throw failure.asServiceException(); }
    }

    /** Called inside export-create's transaction; locks serialize mapping snapshots with replacements. */
    public ExportSnapshot snapshot(long tenant, Query query) {
        validate(query, true);
        repository.lockDevices(tenant, query.deviceIds());
        List<ResolvedBinding> frozen = new ArrayList<>();
        for (ResolvedBinding b : bindings(tenant, query)) {
            IotSourceRegistry.Entry entry = ready(tenant, b.sourceId());
            historyWindow(query, entry.zone());
            frozen.add(new ResolvedBinding(b.deviceId(), b.sourceId(), entry.revision(), entry.zone().getId(), b.mapping()));
        }
        return new ExportSnapshot(1, query, List.copyOf(frozen));
    }

    /** Re-check authorization and identity, never resolve current point mappings for a durable task. */
    public void authorizeSnapshot(long tenant, ExportSnapshot snapshot) {
        if (snapshot == null || snapshot.schemaVersion() != 1 || snapshot.query() == null
                || snapshot.bindings() == null || snapshot.bindings().isEmpty()) throw invalid("无效下载快照，请重新创建任务");
        validate(snapshot.query(), true);
        Set<String> expected = new HashSet<>();
        snapshot.query().deviceIds().forEach(d -> snapshot.query().metricKeys().forEach(m -> expected.add(d + ":" + m)));
        Set<String> actual = new HashSet<>();
        for (ResolvedBinding b : snapshot.bindings()) {
            if (b == null || b.mapping() == null || !expected.contains(b.deviceId() + ":" + b.mapping().metricKey())
                    || !actual.add(b.deviceId() + ":" + b.mapping().metricKey())) throw invalid("无效下载绑定快照");
            Device device = repository.device(tenant, b.deviceId());
            IotSourceRegistry.Entry entry = ready(tenant, b.sourceId());
            if (!Objects.equals(device.sourceId(), b.sourceId()) || !Objects.equals(entry.revision(), b.sourceRevision())
                    || !Objects.equals(entry.zone().getId(), b.timeZone())) {
                throw new SourceFailureException(List.of(error(b.sourceId(), "SOURCE_CHANGED")));
            }
        }
        if (!actual.equals(expected)) throw invalid("下载快照缺少请求的设备指标");
    }

    public QueryResult historySnapshot(long tenant, ExportSnapshot snapshot) {
        authorizeSnapshot(tenant, snapshot);
        return execute(tenant, snapshot.query(), snapshot.bindings(), true, true);
    }

    /** Completed pre-migration CSV is immutable: authorize it without replaying or guessing old mappings. */
    public void authorizeLegacyExport(long tenant, Query query) {
        validate(query, true);
        ready(tenant, "default");
        for (Long id : query.deviceIds()) {
            if (!"default".equals(repository.device(tenant, id).sourceId()))
                throw new SourceFailureException(List.of(error("default", "SOURCE_CHANGED")));
        }
    }

    private IotSourceRegistry.Entry ready(long tenant, String sourceId) {
        IotSourceRegistry.Entry entry;
        try { entry = sources.requireAllowed(tenant, sourceId); }
        catch (ServiceException forbidden) { throw new SourceFailureException(List.of(error(sourceId, "SOURCE_FORBIDDEN"))); }
        if (!entry.configured()) throw new SourceFailureException(List.of(error(sourceId, "SOURCE_UNAVAILABLE")));
        return entry;
    }

    private QueryResult execute(long tenant, Query query, List<ResolvedBinding> bindings, boolean history, boolean frozen) {
        Map<String, List<ResolvedBinding>> groups = bindings.stream().collect(Collectors.groupingBy(
                ResolvedBinding::sourceId, LinkedHashMap::new, Collectors.toList()));
        Map<String, Pending> pending = new LinkedHashMap<>();
        List<SourceError> errors = new ArrayList<>();
        // Each entry has its own bounded pool. Submit all groups before waiting on any source.
        for (var group : groups.entrySet()) {
            String id = group.getKey();
            try {
                IotSourceRegistry.Entry entry = ready(tenant, id);
                if (frozen && group.getValue().stream().anyMatch(b -> !entry.revision().equals(b.sourceRevision())))
                    throw new SourceFailureException(List.of(error(id, "SOURCE_CHANGED")));
                long deadline = System.nanoTime() + entry.requestTimeout().toNanos();
                Future<Completed> future = entry.submit(() -> {
                    List<Series> series=readGroup(entry, query, group.getValue(), history);
                    return new Completed(series, System.nanoTime());
                });
                pending.put(id, new Pending(future, deadline));
            } catch (SourceFailureException failure) { errors.addAll(failure.errors()); }
            catch (RejectedExecutionException busy) { errors.add(error(id, "SOURCE_BUSY")); }
        }
        List<Series> result = new ArrayList<>();
        try {
            for (var item : pending.entrySet()) {
                Pending work = item.getValue();
                try {
                    // A group already completed while another source timed out remains usable.
                    Completed completed = work.future().isDone() ? work.future().get()
                            : work.future().get(Math.max(1, work.deadline() - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (completed.at() > work.deadline()) errors.add(error(item.getKey(), "SOURCE_TIMEOUT"));
                    else result.addAll(completed.series());
                } catch (TimeoutException timeout) {
                    work.future().cancel(true); errors.add(error(item.getKey(), "SOURCE_TIMEOUT"));
                } catch (ExecutionException failure) {
                    // Upstream exception text/bodies can contain secrets: only fixed public codes/messages escape.
                    errors.add(error(item.getKey(), "SOURCE_QUERY_FAILED"));
                } catch (CancellationException cancelled) {
                    errors.add(error(item.getKey(), "SOURCE_TIMEOUT"));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SourceFailureException(groups.keySet().stream().map(id -> error(id, "SOURCE_TIMEOUT")).toList());
        } finally {
            pending.values().forEach(p -> { if (!p.future().isDone()) p.future().cancel(true); });
        }
        if (result.isEmpty() && !errors.isEmpty()) throw new SourceFailureException(errors);
        if (result.stream().mapToLong(s -> s.points().size()).sum() > MAX_SAMPLES) throw invalid("实时库响应超过最大点数");
        return new QueryResult("REALDB", List.copyOf(result), !errors.isEmpty(), List.copyOf(errors));
    }

    private List<Series> readGroup(IotSourceRegistry.Entry entry, Query query, List<ResolvedBinding> bindings, boolean history) {
        List<String> names = bindings.stream().map(b -> b.mapping().pointName()).distinct().toList();
        ZoneId zone = entry.zone();
        JsonNode raw;
        if (history) {
            historyWindow(query, zone);
            raw = entry.client().getHistoryRange(new RealDbHistoryQueryDTO().setNames(names)
                    .setStartTime(local(query.startTime(), zone)).setEndTime(local(query.endTime(), zone))
                    .setInterval(query.intervalMs()).setOptions(new RealDbReadOptionsDTO().setValueOnly(false)));
        } else {
            raw = entry.client().getNamedData(names, new RealDbReadOptionsDTO().setValueOnly(false));
        }
        Map<String, JsonNode> items = indexed(raw, names);
        String receivedAt = Instant.now().toString();
        List<Series> result = new ArrayList<>();
        int count = 0;
        // Reserve each group only its requested share. This also counts pre-aggregation rows,
        // so a malformed large source cannot consume another source's budget or evade it via AVG.
        long groupSamples = history ? (Duration.between(query.startTime().toInstant(),query.endTime().toInstant()).toMillis()
                / query.intervalMs() + 1) * bindings.size() : bindings.size();
        for (ResolvedBinding b : bindings) {
            JsonNode item = items.get(b.mapping().pointName());
            List<Point> points = new ArrayList<>();
            if (!history) {
                points.add(item == null ? new Point(receivedAt, null, "MISSING", "RECEIVED") : point(item, receivedAt, zone));
            } else if (item != null) {
                JsonNode values = item.get("vals");
                if (values == null || !values.isArray()) throw unsupported();
                for (JsonNode value : values) {
                    if (++count > groupSamples) throw invalid("该数据源响应超过请求的采样点预算");
                    Point p = point(value, null, zone);
                    Instant time = Instant.parse(p.time());
                    if (time.isBefore(query.startTime().toInstant()) || time.isAfter(query.endTime().toInstant()))
                        throw invalid("实时库响应时间超出请求范围");
                    points.add(p);
                }
            }
            points.sort(Comparator.comparing(p -> Instant.parse(p.time())));
            result.add(new Series(b.deviceId(), b.mapping().metricKey(), b.mapping().unit(),
                    history ? aggregate(points, query.aggregation()) : List.copyOf(points), b.sourceId()));
        }
        return result;
    }

    private static void historyWindow(Query query, ZoneId zone) {
        local(query.startTime(), zone); local(query.endTime(), zone);
        if (!zone.getRules().getOffset(query.startTime().toInstant()).equals(zone.getRules().getOffset(query.endTime().toInstant())))
            throw invalid("查询窗口跨越实时库时区夏令时切换，请拆分查询");
    }

    private static SourceError error(String sourceId, String code) {
        String message = switch (code) {
            case "SOURCE_FORBIDDEN" -> "数据源不存在或当前租户未获准使用";
            case "SOURCE_UNAVAILABLE" -> "数据源未启用或配置不完整；不会回退演示数据";
            case "SOURCE_BUSY" -> "该数据源并发请求已满，请稍后重试";
            case "SOURCE_TIMEOUT" -> "该数据源查询超时或已取消";
            case "SOURCE_CHANGED" -> "数据源身份已改变，请重新创建下载任务";
            default -> "该数据源查询失败，请核对认证、测点和响应契约";
        };
        return new SourceError(sourceId, code, message);
    }

    public static final class SourceFailureException extends RuntimeException {
        private final List<SourceError> errors;
        public SourceFailureException(List<SourceError> errors) {
            super("数据源查询失败（不会回退演示数据）: " + errors.stream()
                    .map(e -> e.sourceId() + "=" + e.code()).collect(Collectors.joining(", ")));
            this.errors = List.copyOf(errors);
        }
        public List<SourceError> errors() { return errors; }
        public ServiceException asServiceException() { return new ServiceException(1_070_000_002,getMessage()); }
    }

    private record Completed(List<Series> series, long at) {}
    private record Pending(Future<Completed> future, long deadline) {}

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
        // Match the vendor client's millisecond contract before any mapping lookup, queue write or HTTP request.
        // Never truncate an unsupported boundary and silently change the requested history window.
        if (q.startTime().getNano() % 1_000_000 != 0 || q.endTime().getNano() % 1_000_000 != 0) {
            throw invalid("历史查询起止时间最多支持毫秒精度，请显式提供毫秒或更低精度的时间");
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

    private List<ResolvedBinding> bindings(long tenant, Query q) {
        if (tenant <= 0) throw invalid("租户上下文缺失");
        List<ResolvedBinding> result = new ArrayList<>();
        for (Long deviceId : q.deviceIds()) {
            Device device = repository.device(tenant, deviceId);
            if (device == null) throw IotRepository.missing();
            Map<String, Mapping> mappings = new HashMap<>();
            for (Mapping mapping : repository.mappings(tenant, deviceId)) mappings.put(mapping.metricKey(), mapping);
            for (String metric : q.metricKeys()) {
                Mapping mapping = mappings.get(metric);
                if (mapping == null) throw invalid("设备未配置请求的统一指标映射");
                result.add(new ResolvedBinding(deviceId, device.sourceId(), null, null, mapping));
            }
        }
        return result;
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

    private static Point point(JsonNode item, String fallbackTime, ZoneId zone) {
        if (!item.isObject() || !item.has("val")) throw unsupported();
        JsonNode val = item.get("val");
        if (!val.isNull() && !val.isNumber() && !val.isTextual()) throw unsupported();
        Object value = val.isNull() ? null : val.isNumber() ? val.decimalValue() : val.textValue();
        String quality = value == null ? "MISSING" : quality(item.get("quality"));
        String time;
        if (item.hasNonNull("time")) {
            if (!item.get("time").isTextual()) throw unsupported();
            time = instant(item.get("time").textValue(), zone).toString();
        } else {
            if (fallbackTime == null) throw unsupported();
            time = fallbackTime;
        }
        return new Point(time, value, quality, item.hasNonNull("time") ? "DEVICE" : "RECEIVED");
    }

    private static Instant instant(String time, ZoneId zone) {
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

    private static LocalDateTime local(OffsetDateTime time, ZoneId zone) {
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
}
