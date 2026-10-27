package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbReadOptionsDTO;
import cn.iocoder.yudao.framework.realdb.core.exception.RealDbException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Real HTTP, exclusively against ephemeral loopback fixtures; never a live installation or credential. */
class IotSourceRegistryHttpTest {
    @Test void distinctClientsKeepSessionsAndAuthenticationFailuresIsolatedWithoutProbing() throws Exception {
        try (Fixture north = new Fixture("north", 11); Fixture south = new Fixture("south", 22)) {
            var env = environment(north, south);
            var registry = registry(env);
            try {
                registry.sources(11); registry.sources(12);
                assertThat(north.requests.get()).isZero(); assertThat(south.requests.get()).isZero();
                var a = registry.requireAllowed(11, "north").client();
                var b = registry.requireAllowed(11, "south").client();
                assertThat(a).isNotSameAs(b);
                assertThat(value(a)).isEqualTo(11); assertThat(value(b)).isEqualTo(22);
                assertThat(north.logins.get()).isEqualTo(1); assertThat(south.logins.get()).isEqualTo(1);
                north.rejectNext.set(true);
                assertThatThrownBy(() -> value(a)).isInstanceOf(RealDbException.class);
                assertThat(north.logins.get()).isEqualTo(1); // No failed-operation replay or implicit immediate re-login.
                assertThat(value(b)).isEqualTo(22); assertThat(south.logins.get()).isEqualTo(1);
                assertThat(value(a)).isEqualTo(11); assertThat(north.logins.get()).isEqualTo(2);
                assertThat(north.wrongAuthentication.get()).isZero(); assertThat(south.wrongAuthentication.get()).isZero();
            } finally { registry.close(); }
        }
    }
    @Test void oneSourceReadTimeoutDoesNotChangeOtherSourceTimeoutOrSession() throws Exception {
        try (Fixture north = new Fixture("north", 11); Fixture south = new Fixture("south", 22)) {
            var env = environment(north, south)
                    .withProperty("yudao.iot.sources.north.connection.token", "north-token")
                    .withProperty("yudao.iot.sources.north.connection.read-timeout", "20ms")
                    .withProperty("yudao.iot.sources.south.connection.read-timeout", "2s");
            north.delayMillis = 200;
            var registry = registry(env);
            try {
                assertThatThrownBy(() -> value(registry.requireAllowed(11, "north").client())).isInstanceOf(RealDbException.class);
                assertThat(value(registry.requireAllowed(11, "south").client())).isEqualTo(22);
                assertThat(south.logins.get()).isEqualTo(1); assertThat(north.logins.get()).isZero();
            } finally { registry.close(); }
        }
    }
    @Test void busySourceHasBoundedQueueAndDoesNotStarveAnotherSource() throws Exception {
        var a = entry("a"); var b = entry("b");
        CountDownLatch running = new CountDownLatch(2), release = new CountDownLatch(1);
        try {
            Callable<Integer> blocked = () -> { running.countDown(); release.await(); return 1; };
            a.submit(blocked); a.submit(blocked);
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            Future<Integer> queued = a.submit(() -> 2); a.submit(() -> 3);
            assertThatThrownBy(() -> a.submit(() -> 4)).isInstanceOf(RejectedExecutionException.class);
            assertThat(b.submit(() -> 99).get(1, TimeUnit.SECONDS)).isEqualTo(99);
            assertThat(queued.cancel(false)).isTrue(); // Cancellation removes its queue slot immediately.
            Future<Integer> replacement = a.submit(() -> 5);
            release.countDown(); assertThat(replacement.get(2, TimeUnit.SECONDS)).isEqualTo(5);
        } finally { release.countDown(); a.close(); b.close(); }
    }
    private static IotSourceRegistry.Entry entry(String id) {
        return new IotSourceRegistry.Entry(id, id, false, ZoneId.of("UTC"), "fixture", null, Set.of(11L), false, Duration.ofSeconds(1));
    }
    private static int value(RealDbClient client) {
        return client.getNamedData(List.of("shared.PV"), new RealDbReadOptionsDTO().setValueOnly(false))
                .path("items").get(0).path("val").intValue();
    }
    private static MockEnvironment environment(Fixture north, Fixture south) {
        var env = new MockEnvironment().withProperty("yudao.iot.legacy-default-enabled", "false");
        for (Fixture fixture : List.of(north, south)) {
            IotSourceServiceTest.named(env, fixture.id, "11");
            String prefix = "yudao.iot.sources." + fixture.id + ".connection.";
            env.withProperty(prefix + "base-url", fixture.url())
                    .withProperty(prefix + "username", fixture.id + "-reader")
                    .withProperty(prefix + "password", fixture.id + "-fixture-password");
        }
        return env;
    }
    private static IotSourceRegistry registry(MockEnvironment env) {
        return new IotSourceRegistry(env, new DefaultListableBeanFactory().getBeanProvider(RealDbClient.class));
    }
    private static final class Fixture implements AutoCloseable {
        final String id; final HttpServer server;
        final AtomicInteger requests = new AtomicInteger(), logins = new AtomicInteger(), wrongAuthentication = new AtomicInteger();
        final AtomicBoolean rejectNext = new AtomicBoolean();
        volatile int delayMillis;
        Fixture(String id, int value) throws Exception {
            this.id = id;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/", exchange -> {
                requests.incrementAndGet(); String query = exchange.getRequestURI().getRawQuery();
                boolean login = exchange.getRequestURI().getPath().equals("/api/userlogin");
                int status = 200; String body;
                if (login) {
                    logins.incrementAndGet();
                    if (!query.contains("user=" + id + "-reader") || !query.contains("pass=" + id + "-fixture-password")) wrongAuthentication.incrementAndGet();
                    body = "{\"code\":0,\"token\":\"" + id + "-token\"}";
                } else {
                    if (!query.contains("token=" + id + "-token")) wrongAuthentication.incrementAndGet();
                    if (delayMillis > 0) try { Thread.sleep(delayMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    if (rejectNext.getAndSet(false)) { status = 403; body = "{\"code\":1}"; }
                    else body = "{\"items\":[{\"name\":\"shared.PV\",\"val\":" + value + "}]}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
                finally { exchange.close(); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
