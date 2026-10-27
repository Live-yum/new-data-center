package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.realdb.config.RealDbProperties;
import cn.iocoder.yudao.framework.realdb.core.client.DefaultRealDbClient;
import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import cn.iocoder.yudao.module.iot.config.IotSourceProperties;
import cn.iocoder.yudao.module.iot.model.IotModels.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Immutable deployment catalog: one client, authentication cache and bounded executor per installation.
 * Construction and catalog reads do not login, probe or otherwise contact an upstream server.
 * Configuration changes require a controlled service restart; browser requests never supply URLs/credentials.
 */
@Component
public class IotSourceRegistry {
    private static final String PREFIX = "yudao.iot.";
    private static final int MAX_SOURCES = 16;
    private final Map<String, Entry> entries;

    @Autowired
    public IotSourceRegistry(Environment environment, ObjectProvider<RealDbClient> legacyClients) {
        Binder binder = Binder.get(environment);
        Map<String, Entry> configured = new LinkedHashMap<>();
        boolean legacyEnabled = binder.bind(PREFIX + "legacy-default-enabled", Boolean.class).orElse(true);
        // Null means compatibility mode. An explicitly empty list is deny-all, unlike omission.
        List<Long> legacyTenants = binder.bind(PREFIX + "legacy-default-allowed-tenants", Bindable.listOf(Long.class)).orElse(null);
        if (environment.containsProperty(PREFIX + "legacy-default-allowed-tenants") && legacyTenants == null) {
            legacyTenants = List.of();
        }
        RealDbProperties legacy = binder.bind("yudao.realdb", RealDbProperties.class).orElseGet(RealDbProperties::new);
        String legacyZone = environment.getProperty(PREFIX + "realdb-zone", "UTC");
        String legacyRevision = environment.getProperty(PREFIX + "legacy-default-revision", "legacy-v1");
        configured.put("default", create("default", "紫金桥 RealDB", legacyZone, legacyRevision, legacy,
                legacyEnabled ? tenantSet(legacyTenants) : Set.of(), legacyEnabled && legacyTenants == null,
                legacyClients, true));

        // Bind individual entries separately so a bad timeout or malformed URL on A does not disable B.
        Map<String, Object> sourceIds = binder.bind(PREFIX + "sources", Bindable.mapOf(String.class, Object.class)).orElse(Map.of());
        if (sourceIds.size() > MAX_SOURCES) throw new IllegalArgumentException("At most 16 named RealDB sources are supported");
        Set<String> relaxedIds = new HashSet<>(Set.of("default"));
        for (String id : new TreeSet<>(sourceIds.keySet())) {
            // Spring's relaxed property lookup may merge fields of `source-a` and `sourcea`.
            // Conservatively reject case/separator aliases even when brackets could distinguish them.
            // Reject this ambiguity instead of allowing one installation to inherit another's endpoint/account.
            String relaxedId = id == null ? "" : id.replaceAll("[._-]", "").toLowerCase(Locale.ROOT);
            if (relaxedId.isEmpty() || !relaxedIds.add(relaxedId)) {
                throw new IllegalArgumentException("Ambiguous or reserved RealDB source IDs");
            }
            if (id == null || !id.matches("[A-Za-z0-9_.-]{1,64}") || "default".equals(id)) {
                throw new IllegalArgumentException("Invalid or reserved RealDB source ID");
            }
            String prefix = sourcePrefix(binder, id);
            IotSourceProperties properties;
            try {
                properties = binder.bind(prefix, IotSourceProperties.class).orElseGet(IotSourceProperties::new);
            } catch (RuntimeException invalidConfiguration) {
                // Retain independently valid metadata/allowlist, but never a partly bound connection.
                properties = new IotSourceProperties();
                properties.setName(environment.getProperty(prefix + ".name", id));
                try { properties.setAllowedTenants(binder.bind(prefix + ".allowed-tenants", Bindable.listOf(Long.class)).orElse(List.of())); }
                catch (RuntimeException invalidAllowlist) { properties.setAllowedTenants(List.of()); }
                RealDbProperties incomplete = new RealDbProperties();
                incomplete.setEnabled(true);
                properties.setConnection(incomplete);
            }
            configured.put(id, create(id, safeName(properties.getName(), id), properties.getTimeZone(), properties.getRevision(),
                    properties.getConnection(), tenantSet(properties.getAllowedTenants()), false, null, false));
        }
        this.entries = Collections.unmodifiableMap(configured);
    }

    /** Fixture/custom integrations still use the exact same authorization and bounded execution contract. */
    public IotSourceRegistry(Map<String, Entry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public List<Source> sources(long tenant) {
        requireTenant(tenant);
        return entries.values().stream().filter(entry -> entry.allowed(tenant)).map(Entry::metadata).toList();
    }

    public Entry requireAllowed(long tenant, String id) {
        requireTenant(tenant);
        Entry entry = entries.get(id);
        if (entry == null || !entry.allowed(tenant)) {
            throw new ServiceException(1_070_000_404, "数据源不存在或当前租户无权访问");
        }
        return entry;
    }

    @PreDestroy
    public void close() { entries.values().forEach(Entry::close); }

    private static Entry create(String id, String name, String configuredZone, String configuredRevision,
                                RealDbProperties properties, Set<Long> tenants, boolean legacyAllTenants,
                                ObjectProvider<RealDbClient> legacyClients, boolean legacy) {
        ZoneId zone = ZoneId.of("UTC");
        RealDbClient client = null;
        String revision = "UNCONFIGURED";
        Duration requestTimeout = Duration.ofSeconds(25);
        boolean enabled = properties != null && properties.isEnabled();
        try {
            zone = ZoneId.of(configuredZone);
            if (!hasText(configuredRevision) || configuredRevision.length() > 128) throw new IllegalArgumentException();
            validateTimeout(properties.getConnectTimeout(), Duration.ofSeconds(10));
            validateTimeout(properties.getReadTimeout(), Duration.ofSeconds(30));
            Duration completeRequest = properties.getConnectTimeout().plus(properties.getReadTimeout()).multipliedBy(2).plusSeconds(5);
            // Includes queueing, optional login and reading; leave room inside the browser's 30-second timeout.
            // This group budget can cancel an individual read before its configured HTTP timeout expires.
            requestTimeout = completeRequest.compareTo(Duration.ofSeconds(25)) > 0 ? Duration.ofSeconds(25) : completeRequest;
            if (!hasText(properties.getBaseUrl())) throw new IllegalArgumentException();
            URI address = URI.create(properties.getBaseUrl());
            if (!("http".equals(address.getScheme()) || "https".equals(address.getScheme())) || address.getHost() == null
                    || address.getUserInfo() != null || address.getQuery() != null || address.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            // Endpoint, timezone and authentication account changes must not silently reinterpret queued exports.
            // Rotating only password/token intentionally leaves this identity unchanged.
            revision = fingerprint(id, configuredRevision, address.toString().replaceAll("/+$", ""), zone.getId(),
                    Boolean.toString(properties.isSecurityEnabled()), Objects.toString(properties.getUsername(), ""));
            boolean authentication = !properties.isSecurityEnabled() || hasText(properties.getToken())
                    || hasText(properties.getUsername()) && properties.getPassword() != null;
            if (enabled && authentication) {
                client = legacy ? legacyClients.getIfAvailable() : new DefaultRealDbClient(properties, new ObjectMapper());
            }
        } catch (RuntimeException invalidConfiguration) {
            // No logging of configuration exception chains: they can contain URLs or credentials.
            client = null;
        }
        return new Entry(id, name, enabled, zone, revision, client, tenants, legacyAllTenants, requestTimeout);
    }

    private static String sourcePrefix(Binder binder, String id) {
        // CLI/environment dashed names use a normal property element; explicitly bracketed IDs
        // preserve punctuation/case. Spring does not treat those two forms as interchangeable.
        if (id.matches("[a-z0-9-]+")) {
            String dotted = PREFIX + "sources." + id;
            try {
                if (binder.bind(dotted, Bindable.mapOf(String.class, Object.class)).isBound()) return dotted;
            } catch (org.springframework.boot.context.properties.source.InvalidConfigurationPropertyNameException ignored) {
                // A legal opaque ID need not also be a legal relaxed property name.
            }
        }
        return PREFIX + "sources[" + id + "]";
    }
    private static Set<Long> tenantSet(List<Long> tenants) {
        if (tenants == null || tenants.isEmpty()) return Set.of();
        if (tenants.stream().anyMatch(tenant -> tenant == null || tenant <= 0)) return Set.of();
        return Set.copyOf(tenants);
    }
    private static void requireTenant(long tenant) {
        if (tenant <= 0) throw new ServiceException(1_070_000_001, "租户上下文缺失");
    }
    private static void validateTimeout(Duration duration, Duration maximum) {
        if (duration == null || duration.isZero() || duration.isNegative() || duration.compareTo(maximum) > 0) {
            throw new IllegalArgumentException();
        }
    }
    private static String safeName(String name, String fallback) {
        return hasText(name) && name.length() <= 100 && name.chars().noneMatch(Character::isISOControl) ? name : fallback;
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static String fingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Never serialized as HTTP metadata: contains the private client and internal source identity. */
    public static final class Entry implements AutoCloseable {
        private final String id, name, revision;
        private final boolean enabled, legacyAllTenants;
        private final ZoneId zone;
        private final RealDbClient client;
        private final Set<Long> allowedTenants;
        private final Duration requestTimeout;
        private final ThreadPoolExecutor executor;

        public Entry(String id, String name, boolean enabled, ZoneId zone, String revision, RealDbClient client,
                     Set<Long> allowedTenants, boolean legacyAllTenants, Duration requestTimeout) {
            this.id = id; this.name = name; this.enabled = enabled; this.zone = zone; this.revision = revision;
            this.client = client; this.allowedTenants = Set.copyOf(allowedTenants); this.legacyAllTenants = legacyAllTenants;
            this.requestTimeout = requestTimeout;
            AtomicInteger sequence = new AtomicInteger();
            this.executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "iot-realdb-" + id + "-" + sequence.incrementAndGet());
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            this.executor.allowCoreThreadTimeOut(true);
        }
        public String id() { return id; }
        public String name() { return name; }
        public boolean enabled() { return enabled; }
        public boolean configured() { return enabled && client != null; }
        public ZoneId zone() { return zone; }
        /** Opaque fingerprint, not the operator's plain revision label; snapshot internally only. */
        public String revision() { return revision; }
        public String identity() { return revision; }
        public RealDbClient client() { return client; }
        public Duration requestTimeout() { return requestTimeout; }
        private boolean allowed(long tenant) { return legacyAllTenants || allowedTenants.contains(tenant); }
        public <T> Future<T> submit(Callable<T> task) {
            FutureTask<T> future = new FutureTask<>(task) {
                @Override protected void done() { executor.remove(this); }
            };
            executor.execute(future);
            return future;
        }
        private Source metadata() {
            String status = !enabled ? "DISABLED" : configured() ? "CONFIGURED" : "NOT_CONFIGURED";
            String description = !enabled ? "部署尚未启用此紫金桥数据源；不会回退其他源或模拟数据"
                    : configured() ? "已配置但未验证连接、点权限或现场数据；此状态不发起上游探测"
                    : "此源的部署连接、认证或时区配置不完整；请由运维核对服务端配置";
            return new Source(id, name, "REALDB", enabled, configured(), status, zone.getId(), description);
        }
        @Override public void close() {
            executor.shutdownNow().forEach(task -> { if (task instanceof Future<?> future) future.cancel(false); });
        }
    }
}
