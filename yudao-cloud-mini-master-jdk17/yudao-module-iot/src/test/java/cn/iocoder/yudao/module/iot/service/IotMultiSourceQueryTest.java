package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbHistoryQueryDTO;
import cn.iocoder.yudao.module.iot.model.IotModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Controlled clients only: no external source, credential or real installation is contacted. */
class IotMultiSourceQueryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final OffsetDateTime start = OffsetDateTime.parse("2026-10-26T02:00:00Z");
    private IotRepository repository;
    private RealDbClient alpha, beta;
    private IotSourceRegistry registry;
    private IotSourceRegistry.Entry alphaEntry, betaEntry;
    private IotQueryService service;

    @BeforeEach
    void setup() {
        repository = mock(IotRepository.class);
        alpha = mock(RealDbClient.class); beta = mock(RealDbClient.class);
        bind(1L, "source-a"); bind(2L, "source-b"); bind(3L, "source-a");
        configure(true, Set.of(9L), Duration.ofSeconds(3), "UTC", "Asia/Shanghai");
    }

    @AfterEach
    void cleanup() { registry.close(); }

    private void bind(long id, String source) {
        when(repository.device(9L, id)).thenReturn(new Device(id,"Pump " + id,"P" + id,source));
        when(repository.mappings(9L,id)).thenReturn(List.of(new Mapping("temperature","Temperature","Shared.PV","C")));
    }

    private void configure(boolean alphaEnabled, Set<Long> betaTenants, Duration alphaTimeout, String alphaZone, String betaZone) {
        configure(alphaEnabled,betaTenants,alphaTimeout,Duration.ofSeconds(3),alphaZone,betaZone);
    }

    private void configure(boolean alphaEnabled, Set<Long> betaTenants, Duration alphaTimeout, Duration betaTimeout,
                           String alphaZone, String betaZone) {
        if (registry != null) registry.close();
        alphaEntry = new IotSourceRegistry.Entry("source-a","A",alphaEnabled,ZoneId.of(alphaZone),"a-v1",
                alphaEnabled ? alpha : null,Set.of(9L),false,alphaTimeout);
        betaEntry = new IotSourceRegistry.Entry("source-b","B",true,ZoneId.of(betaZone),"b-v1",
                beta,betaTenants,false,betaTimeout);
        registry = new IotSourceRegistry(new LinkedHashMap<>(Map.of("source-a",alphaEntry,"source-b",betaEntry)));
        service = new IotQueryService(repository,registry);
    }

    private Query query(Long... devices) { return new Query(List.of(devices),List.of("temperature"),start,start.plusMinutes(1),1000L,"RAW"); }
    private JsonNode realtime(int value) throws Exception { return json.readTree("{\"items\":[{\"name\":\"Shared.PV\",\"val\":" + value + ",\"quality\":\"GOOD\"}]}"); }
    private JsonNode history(String time,int value) throws Exception { return json.readTree("{\"items\":[{\"name\":\"Shared.PV\",\"vals\":[{\"time\":\"" + time + "\",\"val\":" + value + ",\"quality\":\"GOOD\"}]}]}"); }
    private Series series(QueryResult result,long device) { return result.series().stream().filter(s->s.deviceId()==device).findFirst().orElseThrow(); }

    @Test
    void identicalPointNamesAreDeduplicatedOnlyWithinTheirOwnSource() throws Exception {
        when(alpha.getNamedData(anyList(),any())).thenReturn(realtime(10));
        when(beta.getNamedData(anyList(),any())).thenReturn(realtime(1010));
        QueryResult result = service.realtime(9,query(1L,2L,3L));
        assertThat(result.partial()).isFalse(); assertThat(result.errors()).isEmpty();
        assertThat(result.series()).hasSize(3);
        assertThat(series(result,1).sourceId()).isEqualTo("source-a");
        assertThat(series(result,2).sourceId()).isEqualTo("source-b");
        assertThat(series(result,1).points().get(0).value()).isEqualTo(new BigDecimal("10"));
        assertThat(series(result,3).points().get(0).value()).isEqualTo(new BigDecimal("10"));
        assertThat(series(result,2).points().get(0).value()).isEqualTo(new BigDecimal("1010"));
        verify(alpha,times(1)).getNamedData(eq(List.of("Shared.PV")),any());
        verify(beta,times(1)).getNamedData(eq(List.of("Shared.PV")),any());
        verifyNoMoreInteractions(alpha,beta);
    }

    @Test
    void historyUsesEachSourceTimezoneWithoutMixingSameNamedPoints() throws Exception {
        when(alpha.getHistoryRange(any())).thenReturn(history("2026-10-26T02:00:00",10));
        when(beta.getHistoryRange(any())).thenReturn(history("2026-10-26T10:00:00",1010));
        QueryResult result=service.history(9,query(1L,2L));
        assertThat(result.errors()).isEmpty();
        for(Series series:result.series()) assertThat(series.points().get(0).time()).isEqualTo("2026-10-26T02:00:00Z");
        assertThat(series(result,2).points().get(0).value()).isEqualTo(new BigDecimal("1010"));
        ArgumentCaptor<RealDbHistoryQueryDTO> a=ArgumentCaptor.forClass(RealDbHistoryQueryDTO.class);
        ArgumentCaptor<RealDbHistoryQueryDTO> b=ArgumentCaptor.forClass(RealDbHistoryQueryDTO.class);
        verify(alpha).getHistoryRange(a.capture()); verify(beta).getHistoryRange(b.capture());
        assertThat(a.getValue().getStartTime()).isEqualTo(start.toLocalDateTime());
        assertThat(b.getValue().getStartTime()).isEqualTo(start.plusHours(8).toLocalDateTime());
    }

    @Test
    void aggregateQueriesStillEnforceTheActualCombinedRawSampleLimit() throws Exception {
        configure(true,Set.of(9L),Duration.ofSeconds(3),"UTC","UTC");
        var body=json.createObjectNode();
        var vals=body.putArray("items").addObject().put("name","Shared.PV").putArray("vals");
        for(int index=0;index<6000;index++) vals.addObject()
                .put("time",start.plusNanos(index * 1_000_000L).toLocalDateTime().toString())
                .put("val",index).put("quality","GOOD");
        when(alpha.getHistoryRange(any())).thenReturn(body);
        when(beta.getHistoryRange(any())).thenReturn(body);
        Query request=new Query(List.of(1L,2L),List.of("temperature"),start,start.plusMinutes(1),1000L,"AVG");
        assertThatThrownBy(()->service.history(9,request)).isInstanceOfSatisfying(ServiceException.class,
                error->assertThat(error.getCode()).isEqualTo(1_070_000_002))
                .hasMessageContaining("source-a=SOURCE_QUERY_FAILED").hasMessageContaining("source-b=SOURCE_QUERY_FAILED");
        verify(alpha,times(1)).getHistoryRange(any());verify(beta,times(1)).getHistoryRange(any());
    }

    @Test
    void oversizedSourceResponseCannotConsumeTheHealthySourcesSampleBudget() throws Exception {
        configure(true,Set.of(9L),Duration.ofSeconds(3),"UTC","UTC");
        var body=json.createObjectNode();
        var vals=body.putArray("items").addObject().put("name","Shared.PV").putArray("vals");
        for(int index=0;index<100;index++) vals.addObject()
                .put("time",start.plusNanos(index * 1_000_000L).toLocalDateTime().toString()).put("val",index);
        when(alpha.getHistoryRange(any())).thenReturn(body);
        when(beta.getHistoryRange(any())).thenReturn(history("2026-10-26T02:00:00",1010));
        QueryResult result=service.history(9,new Query(List.of(1L,2L),List.of("temperature"),start,start.plusMinutes(1),1000L,"AVG"));
        assertThat(result.partial()).isTrue();
        assertThat(result.errors()).singleElement().satisfies(error->{
            assertThat(error.sourceId()).isEqualTo("source-a");assertThat(error.code()).isEqualTo("SOURCE_QUERY_FAILED");
        });
        assertThat(result.series()).singleElement().satisfies(series->{
            assertThat(series.sourceId()).isEqualTo("source-b");assertThat(series.points().get(0).value()).isEqualTo(new BigDecimal("1010"));
        });
    }

    @Test
    void failedSourceIsExplicitAndNeverReplacedByAnotherSourcesSamePoint() throws Exception {
        when(alpha.getNamedData(anyList(),any())).thenThrow(new IllegalStateException("secret-token https://secret.internal/password"));
        when(beta.getNamedData(anyList(),any())).thenReturn(realtime(1010));
        QueryResult result=service.realtime(9,query(1L,2L));
        assertThat(result.partial()).isTrue(); assertThat(result.series()).hasSize(1);
        assertThat(result.series().get(0).deviceId()).isEqualTo(2L);
        assertThat(result.errors()).singleElement().satisfies(error->{
            assertThat(error.sourceId()).isEqualTo("source-a");
            assertThat(error.code()).isEqualTo("SOURCE_QUERY_FAILED");
        });
        assertThat(json.writeValueAsString(result)).doesNotContain("secret-token","secret.internal","password");
        verify(alpha,times(1)).getNamedData(anyList(),any()); verify(beta,times(1)).getNamedData(anyList(),any());
        verifyNoMoreInteractions(alpha,beta);
    }

    @Test
    void allFailedSourcesFailTheRequestRatherThanReportSuccessfulEmptyData() {
        when(alpha.getNamedData(anyList(),any())).thenThrow(new IllegalStateException("secret-a"));
        when(beta.getNamedData(anyList(),any())).thenThrow(new IllegalStateException("secret-b"));
        assertThatThrownBy(()->service.realtime(9,query(1L,2L)))
                .isInstanceOfSatisfying(ServiceException.class,error->assertThat(error.getCode()).isEqualTo(1_070_000_002))
                .hasMessageContaining("source-a").hasMessageContaining("source-b")
                .hasMessageContaining("SOURCE_QUERY_FAILED").hasMessageNotContaining("secret");
        verify(alpha,times(1)).getNamedData(anyList(),any()); verify(beta,times(1)).getNamedData(anyList(),any());
    }

    @Test
    void disabledSourceDoesNotFallbackToHealthySource() throws Exception {
        configure(false,Set.of(9L),Duration.ofSeconds(3),"UTC","Asia/Shanghai");
        when(beta.getNamedData(anyList(),any())).thenReturn(realtime(1010));
        QueryResult result=service.realtime(9,query(1L,2L));
        assertThat(result.partial()).isTrue();
        assertThat(result.errors()).singleElement().satisfies(error->assertThat(error.code()).isEqualTo("SOURCE_UNAVAILABLE"));
        assertThat(result.series()).singleElement().satisfies(series->assertThat(series.sourceId()).isEqualTo("source-b"));
        verifyNoInteractions(alpha); verify(beta,times(1)).getNamedData(anyList(),any());
    }

    @Test
    void revokedTenantSourceAccessIsRecheckedBeforeSendingItsPoints() throws Exception {
        configure(true,Set.of(10L),Duration.ofSeconds(3),"UTC","Asia/Shanghai");
        when(alpha.getNamedData(anyList(),any())).thenReturn(realtime(10));
        QueryResult result=service.realtime(9,query(1L,2L));
        assertThat(result.partial()).isTrue();
        assertThat(result.errors()).singleElement().satisfies(error->{
            assertThat(error.sourceId()).isEqualTo("source-b"); assertThat(error.code()).isEqualTo("SOURCE_FORBIDDEN");
        });
        assertThat(result.series()).singleElement().satisfies(series->assertThat(series.sourceId()).isEqualTo("source-a"));
        verifyNoInteractions(beta);
    }

    @Test
    void malformedSourceHistoryDoesNotDiscardAnotherSourcesValidHistory() throws Exception {
        when(alpha.getHistoryRange(any())).thenReturn(json.readTree("{\"items\":[{\"name\":\"Shared.PV\",\"vals\":[{\"val\":10}]}]}"));
        when(beta.getHistoryRange(any())).thenReturn(history("2026-10-26T10:00:00",1010));
        QueryResult result=service.history(9,query(1L,2L));
        assertThat(result.partial()).isTrue();
        assertThat(result.errors()).singleElement().satisfies(error->{
            assertThat(error.sourceId()).isEqualTo("source-a"); assertThat(error.code()).isEqualTo("SOURCE_QUERY_FAILED");
        });
        assertThat(result.series()).singleElement().satisfies(series->{
            assertThat(series.sourceId()).isEqualTo("source-b");
            assertThat(series.points().get(0).time()).isEqualTo("2026-10-26T02:00:00Z");
        });
    }

    @Test
    void dstAmbiguityRejectsOnlyTheAffectedSourceBeforeItsRemoteRead() throws Exception {
        configure(true,Set.of(9L),Duration.ofSeconds(3),"America/New_York","UTC");
        OffsetDateTime ambiguous=OffsetDateTime.parse("2026-11-01T05:15:00Z");
        Query request=new Query(List.of(1L,2L),List.of("temperature"),ambiguous,ambiguous.plusMinutes(1),1000L,"RAW");
        when(beta.getHistoryRange(any())).thenReturn(history("2026-11-01T05:15:00",1010));
        QueryResult result=service.history(9,request);
        assertThat(result.errors()).singleElement().satisfies(error->assertThat(error.sourceId()).isEqualTo("source-a"));
        assertThat(result.series()).singleElement().satisfies(series->assertThat(series.sourceId()).isEqualTo("source-b"));
        verifyNoInteractions(alpha); verify(beta).getHistoryRange(any());
    }

    @Test
    void slowSourceCannotPreventOtherSourcesFromStarting() throws Exception {
        CountDownLatch betaStarted=new CountDownLatch(1);
        JsonNode a=realtime(10), b=realtime(1010);
        when(alpha.getNamedData(anyList(),any())).thenAnswer(invocation->{
            assertThat(betaStarted.await(2,TimeUnit.SECONDS)).as("B must start before A is released").isTrue();
            return a;
        });
        when(beta.getNamedData(anyList(),any())).thenAnswer(invocation->{betaStarted.countDown();return b;});
        QueryResult result=service.realtime(9,query(1L,2L));
        assertThat(result.partial()).isFalse(); assertThat(result.errors()).isEmpty(); assertThat(result.series()).hasSize(2);
    }

    @Test
    void timedOutSourceIsCancelledWithoutReplayAndHealthySourceRemainsAvailable() throws Exception {
        configure(true,Set.of(9L),Duration.ofMillis(150),"UTC","Asia/Shanghai");
        CountDownLatch interrupted=new CountDownLatch(1);
        when(alpha.getNamedData(anyList(),any())).thenAnswer(invocation->{
            try { new CountDownLatch(1).await(5,TimeUnit.SECONDS); throw new IllegalStateException("unexpected release"); }
            catch(InterruptedException expected) {interrupted.countDown();Thread.currentThread().interrupt();throw new IllegalStateException("interrupted");}
        });
        when(beta.getNamedData(anyList(),any())).thenReturn(realtime(1010));
        long began=System.nanoTime(); QueryResult result=service.realtime(9,query(1L,2L));
        assertThat(Duration.ofNanos(System.nanoTime()-began)).isLessThan(Duration.ofSeconds(2));
        assertThat(result.errors()).singleElement().satisfies(error->assertThat(error.code()).isEqualTo("SOURCE_TIMEOUT"));
        assertThat(interrupted.await(1,TimeUnit.SECONDS)).isTrue();
        assertThat(result.series()).singleElement().satisfies(series->assertThat(series.sourceId()).isEqualTo("source-b"));
        verify(alpha,times(1)).getNamedData(anyList(),any()); verify(beta,times(1)).getNamedData(anyList(),any());
    }

    @Test
    void completionAfterOwnDeadlineCannotHideBehindAnEarlierSourcesLongerTimeout() throws Exception {
        configure(true,Set.of(9L),Duration.ofMillis(200),Duration.ofMillis(30),"UTC","UTC");
        CountDownLatch betaFinished=new CountDownLatch(1);
        JsonNode late=realtime(1010);
        when(alpha.getNamedData(anyList(),any())).thenAnswer(invocation->{
            new CountDownLatch(1).await(5,TimeUnit.SECONDS);return late;
        });
        when(beta.getNamedData(anyList(),any())).thenAnswer(invocation->{
            // B finishes after its own 30ms deadline but before A's 200ms wait ends.
            new CountDownLatch(1).await(60,TimeUnit.MILLISECONDS);betaFinished.countDown();return late;
        });
        assertThatThrownBy(()->service.realtime(9,query(1L,2L)))
                .isInstanceOfSatisfying(ServiceException.class,error->assertThat(error.getCode()).isEqualTo(1_070_000_002))
                .hasMessageContaining("source-a=SOURCE_TIMEOUT").hasMessageContaining("source-b=SOURCE_TIMEOUT");
        assertThat(betaFinished.await(1,TimeUnit.SECONDS)).isTrue();
        verify(alpha,times(1)).getNamedData(anyList(),any());verify(beta,times(1)).getNamedData(anyList(),any());
    }

    @Test
    void saturatedSourcePoolCannotExhaustAnotherSourcePool() throws Exception {
        CountDownLatch entered=new CountDownLatch(2), release=new CountDownLatch(1);
        Callable<Void> blocker=()->{entered.countDown();release.await(5,TimeUnit.SECONDS);return null;};
        List<Future<Void>> occupied=new ArrayList<>();
        try {
            occupied.add(alphaEntry.submit(blocker)); occupied.add(alphaEntry.submit(blocker));
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            occupied.add(alphaEntry.submit(blocker)); occupied.add(alphaEntry.submit(blocker));
            when(beta.getNamedData(anyList(),any())).thenReturn(realtime(1010));
            QueryResult result=service.realtime(9,query(1L,2L));
            assertThat(result.errors()).singleElement().satisfies(error->assertThat(error.code()).isEqualTo("SOURCE_BUSY"));
            assertThat(result.series()).singleElement().satisfies(series->assertThat(series.sourceId()).isEqualTo("source-b"));
            verifyNoInteractions(alpha); verify(beta,times(1)).getNamedData(anyList(),any());
        } finally {release.countDown();occupied.forEach(future->future.cancel(true));}
    }
}
