package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import cn.iocoder.yudao.module.iot.model.IotModels.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IotSourceServiceTest {
    private IotSourceRegistry registry(MockEnvironment environment, RealDbClient client) {
        var beans = new DefaultListableBeanFactory();
        if (client != null) beans.registerSingleton("realDb", client);
        return new IotSourceRegistry(environment, beans.getBeanProvider(RealDbClient.class));
    }
    @Test void disabledSourceNeverInstantiatesOrContactsClient() {
        var registry = registry(new MockEnvironment(), null);
        try {
            Source source = new IotSourceService(registry).sources(11).get(0);
            assertThat(source.status()).isEqualTo("DISABLED"); assertThat(source.enabled()).isFalse();
            assertThat(source.configured()).isFalse(); assertThat(source.timeZone()).isEqualTo("UTC");
        } finally { registry.close(); }
    }
    @Test void configurationStatusDoesNotClaimConnectivityOrRevealSecrets() throws Exception {
        RealDbClient client = mock(RealDbClient.class);
        var env = new MockEnvironment().withProperty("yudao.realdb.enabled", "true")
                .withProperty("yudao.realdb.base-url", "https://sensitive-upstream.invalid")
                .withProperty("yudao.realdb.username", "sensitive-user").withProperty("yudao.realdb.password", "sensitive-password")
                .withProperty("yudao.realdb.token", "sensitive-token").withProperty("yudao.iot.realdb-zone", "Asia/Shanghai");
        var registry = registry(env, client);
        try {
            var sources = new IotSourceService(registry).sources(11);
            assertThat(sources).hasSize(1); assertThat(sources.get(0).status()).isEqualTo("CONFIGURED");
            assertThat(sources.get(0).timeZone()).isEqualTo("Asia/Shanghai");
            assertThat(new ObjectMapper().writeValueAsString(sources)).doesNotContain("sensitive-", "http", "password", "token", "username");
            verifyNoInteractions(client);
        } finally { registry.close(); }
    }
    @Test void incompleteAuthenticationIsNotConfiguredAndConfigurationIsRestartScoped() {
        RealDbClient client = mock(RealDbClient.class);
        var env = new MockEnvironment().withProperty("yudao.realdb.enabled", "true").withProperty("yudao.realdb.base-url", "http://127.0.0.1:1");
        var incomplete = registry(env, client);
        try {
            assertThat(incomplete.sources(11).get(0).status()).isEqualTo("NOT_CONFIGURED");
            env.withProperty("yudao.realdb.token", "test-token");
            assertThat(incomplete.sources(11).get(0).configured()).isFalse();
            var tokenOnly = registry(env, client);
            try { assertThat(tokenOnly.sources(11).get(0).configured()).isTrue(); }
            finally { tokenOnly.close(); }
            env.withProperty("yudao.realdb.token", "").withProperty("yudao.realdb.security-enabled", "false");
            var unsecured = registry(env, client);
            try { assertThat(unsecured.sources(11).get(0).configured()).isTrue(); }
            finally { unsecured.close(); }
            verifyNoInteractions(client);
        } finally { incomplete.close(); }
    }
    @Test void legacyDefaultCanBeExplicitlyRestrictedOrDisabledWithoutImplicitNamedSourceGrants() {
        var env = new MockEnvironment().withProperty("yudao.iot.legacy-default-allowed-tenants", "11");
        var allowed = registry(env, null);
        try {
            assertThat(allowed.sources(11)).hasSize(1); assertThat(allowed.sources(12)).isEmpty();
            assertThatThrownBy(() -> allowed.requireAllowed(12, "default")).isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> allowed.sources(0)).isInstanceOf(ServiceException.class);
        } finally { allowed.close(); }
        env.withProperty("yudao.iot.legacy-default-allowed-tenants", "");
        var empty = registry(env, null);
        try { assertThat(empty.sources(11)).isEmpty(); } finally { empty.close(); }
        env.withProperty("yudao.iot.legacy-default-enabled", "false").withProperty("yudao.iot.legacy-default-allowed-tenants", "11");
        var disabled = registry(env, null);
        try { assertThat(disabled.sources(11)).isEmpty(); } finally { disabled.close(); }
    }
    @Test void namedAllowlistIsMandatoryAndBrokenSourceDoesNotDisableGoodSource() {
        var env = named(new MockEnvironment().withProperty("yudao.iot.legacy-default-enabled", "false"), "north", "11,12");
        named(env, "south", "12"); named(env, "hidden", ""); named(env, "broken", "11");
        env.withProperty("yudao.iot.sources.broken.connection.read-timeout", "not-a-duration");
        var registry = registry(env, null);
        try {
            assertThat(registry.sources(11)).extracting(Source::id).containsExactly("broken", "north");
            assertThat(registry.sources(12)).extracting(Source::id).containsExactly("north", "south");
            assertThat(registry.requireAllowed(11, "north").configured()).isTrue();
            assertThat(registry.requireAllowed(11, "broken").configured()).isFalse();
            for (String denied : List.of("south", "hidden", "unknown", "https://evil.invalid")) {
                assertThatThrownBy(() -> registry.requireAllowed(11, denied)).isInstanceOf(ServiceException.class)
                        .hasMessage("数据源不存在或当前租户无权访问");
            }
            assertThat(registry.requireAllowed(11, "north").client()).isNotSameAs(registry.requireAllowed(12, "south").client());
        } finally { registry.close(); }
    }
    @Test void missingOrInvalidConnectionFieldsAreIsolatedFromHealthySources() {
        for (var invalid : List.of(
                java.util.Map.entry("connection.base-url", ""),
                java.util.Map.entry("connection.base-url", "not-a-url"),
                java.util.Map.entry("connection.base-url", "http://user:secret@127.0.0.1:1"),
                java.util.Map.entry("connection.username", ""),
                java.util.Map.entry("time-zone", "Invalid/Timezone"),
                java.util.Map.entry("revision", ""))) {
            var env = named(named(new MockEnvironment(), "north", "11"), "broken", "11");
            env.withProperty("yudao.iot.sources.broken." + invalid.getKey(), invalid.getValue());
            var registry = registry(env, null);
            try {
                assertThat(registry.requireAllowed(11, "north").configured()).isTrue();
                assertThat(registry.requireAllowed(11, "broken").configured()).isFalse();
                assertThat(registry.sources(11)).filteredOn(source -> source.id().equals("broken"))
                        .extracting(Source::status).containsExactly("NOT_CONFIGURED");
            } finally { registry.close(); }
        }
    }
    @Test void identityChangesForEndpointTimezonePrincipalOrRevisionButNotCredentialRotation() {
        var env = named(new MockEnvironment(), "north", "11");
        String original = revision(env);
        env.withProperty("yudao.iot.sources.north.connection.password", "rotated").withProperty("yudao.iot.sources.north.connection.token", "rotated");
        assertThat(revision(env)).isEqualTo(original);
        env.withProperty("yudao.iot.sources.north.connection.base-url", "http://127.0.0.1:2");
        String endpoint = revision(env); assertThat(endpoint).isNotEqualTo(original);
        env.withProperty("yudao.iot.sources.north.time-zone", "Asia/Shanghai");
        String zone = revision(env); assertThat(zone).isNotEqualTo(endpoint);
        env.withProperty("yudao.iot.sources.north.connection.username", "changed-reader");
        String principal = revision(env); assertThat(principal).isNotEqualTo(zone);
        env.withProperty("yudao.iot.sources.north.revision", "installation-2");
        assertThat(revision(env)).isNotEqualTo(principal);
    }
    @Test void cliDotStyleSourceNamesAndIndexedTenantAllowlistsBindExactly() {
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.SimpleCommandLinePropertySource(
                "--yudao.iot.legacy-default-enabled=false",
                "--yudao.iot.sources.source-a.name=Fixture A",
                "--yudao.iot.sources.source-a.time-zone=UTC",
                "--yudao.iot.sources.source-a.revision=fixture-v1",
                "--yudao.iot.sources.source-a.allowed-tenants[0]=7001",
                "--yudao.iot.sources.source-a.connection.enabled=true",
                "--yudao.iot.sources.source-a.connection.base-url=http://127.0.0.1:48084",
                "--yudao.iot.sources.source-a.connection.token=ci-disposable-realdb-source-a-token",
                "--yudao.iot.sources.source-a.connection.security-enabled=true",
                "--yudao.iot.sources.source-a.connection.read-timeout=4s",
                "--yudao.iot.sources.source-a.connection.connect-timeout=1s",
                "--yudao.iot.sources.source-b.revision=fixture-v1",
                "--yudao.iot.sources.source-b.allowed-tenants[0]=7001",
                "--yudao.iot.sources.source-b.allowed-tenants[1]=7002",
                "--yudao.iot.sources.source-b.connection.enabled=true",
                "--yudao.iot.sources.source-b.connection.base-url=http://127.0.0.1:48085",
                "--yudao.iot.sources.source-b.connection.token=ci-disposable-realdb-source-b-token",
                "--yudao.iot.sources.disabled.allowed-tenants[0]=7001",
                "--yudao.iot.sources.disabled.connection.enabled=false"));
        var registry = new IotSourceRegistry(environment, new DefaultListableBeanFactory().getBeanProvider(RealDbClient.class));
        try {
            assertThat(registry.sources(7001)).extracting(Source::id).containsExactly("disabled", "source-a", "source-b");
            assertThat(registry.sources(7002)).extracting(Source::id).containsExactly("source-b");
            assertThat(registry.requireAllowed(7001, "source-a").configured()).isTrue();
            assertThat(registry.requireAllowed(7001, "source-a").requestTimeout()).isEqualTo(java.time.Duration.ofSeconds(15));
            assertThat(registry.requireAllowed(7002, "source-b").configured()).isTrue();
            assertThat(registry.sources(7001)).filteredOn(source -> source.id().equals("disabled"))
                    .extracting(Source::status).containsExactly("DISABLED");
        } finally { registry.close(); }
    }
    @Test void bracketedSourceIdsPreserveCaseAndDotsWithoutNamespaceAliasing() {
        var env = new MockEnvironment().withProperty("yudao.iot.legacy-default-enabled", "false");
        String prefix = "yudao.iot.sources[North.Site]";
        env.withProperty(prefix + ".revision", "fixture-1").withProperty(prefix + ".allowed-tenants", "11")
                .withProperty(prefix + ".connection.enabled", "true").withProperty(prefix + ".connection.security-enabled", "false")
                .withProperty(prefix + ".connection.base-url", "http://127.0.0.1:1");
        var registry = registry(env, null);
        try {
            assertThat(registry.sources(11)).extracting(Source::id).containsExactly("North.Site");
            assertThat(registry.requireAllowed(11, "North.Site").configured()).isTrue();
            assertThatThrownBy(() -> registry.requireAllowed(11, "north.site")).isInstanceOf(ServiceException.class);
        } finally { registry.close(); }
    }
    @Test void relaxedIdAliasesCannotInheritAnotherSourcesConnectionOrAllowlist() {
        var environment = named(named(new MockEnvironment(), "source-a", "11"), "sourcea", "12");
        environment.setProperty("yudao.iot.sources.sourcea.connection.base-url", "");
        assertThatThrownBy(() -> registry(environment, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ambiguous or reserved RealDB source IDs");
        for (String alias : List.of("source_a", "source.a", "SourceA")) {
            var bracketed = named(new MockEnvironment(), "sourcea", "11")
                    .withProperty("yudao.iot.sources[" + alias + "].allowed-tenants", "12");
            assertThatThrownBy(() -> registry(bracketed, null)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Ambiguous or reserved RealDB source IDs");
        }
        assertThatThrownBy(() -> registry(named(new MockEnvironment(), "de-fault", "11"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void productionDeadlineIncludesQueueAndAuthenticationAndStaysBelowBrowserTimeout() {
        var env = named(new MockEnvironment(), "north", "11");
        var defaults = registry(env, null);
        try { assertThat(defaults.requireAllowed(11, "north").requestTimeout()).isEqualTo(java.time.Duration.ofSeconds(25)); }
        finally { defaults.close(); }
        env.withProperty("yudao.iot.sources.north.connection.connect-timeout", "10s")
                .withProperty("yudao.iot.sources.north.connection.read-timeout", "30s");
        var maximum = registry(env, null);
        try { assertThat(maximum.requireAllowed(11, "north").requestTimeout()).isEqualTo(java.time.Duration.ofSeconds(25)); }
        finally { maximum.close(); }
        env.withProperty("yudao.iot.sources.north.connection.connect-timeout", "1s")
                .withProperty("yudao.iot.sources.north.connection.read-timeout", "2s");
        var shorter = registry(env, null);
        try { assertThat(shorter.requireAllowed(11, "north").requestTimeout()).isEqualTo(java.time.Duration.ofSeconds(11)); }
        finally { shorter.close(); }
    }
    @Test void reservedDefaultCollisionAndUnboundedTimeoutFailClosed() {
        assertThatThrownBy(() -> registry(named(new MockEnvironment(), "default", "11"), null)).isInstanceOf(IllegalArgumentException.class);
        var env = named(new MockEnvironment(), "north", "11").withProperty("yudao.iot.sources.north.connection.read-timeout", "31s");
        var registry = registry(env, null);
        try { assertThat(registry.requireAllowed(11, "north").configured()).isFalse(); }
        finally { registry.close(); }
    }
    private String revision(MockEnvironment env) {
        var registry = registry(env, null);
        try { return registry.requireAllowed(11, "north").revision(); } finally { registry.close(); }
    }
    static MockEnvironment named(MockEnvironment env, String id, String tenants) {
        String p = "yudao.iot.sources." + id;
        return env.withProperty(p + ".name", "Source " + id).withProperty(p + ".revision", "installation-1")
                .withProperty(p + ".allowed-tenants", tenants).withProperty(p + ".connection.enabled", "true")
                .withProperty(p + ".connection.base-url", "http://127.0.0.1:1")
                .withProperty(p + ".connection.username", "reader").withProperty(p + ".connection.password", "fixture-only");
    }
}
