package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.realdb.core.client.RealDbClient;
import cn.iocoder.yudao.framework.realdb.core.dto.RealDbHistoryQueryDTO;
import cn.iocoder.yudao.module.iot.model.IotModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IotQueryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private IotRepository repository;
    private RealDbClient client;
    private ObjectProvider<RealDbClient> provider;
    private IotQueryService service;
    private final OffsetDateTime start = OffsetDateTime.parse("2026-10-26T10:00:00+08:00");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        repository = mock(IotRepository.class);
        client = mock(RealDbClient.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(repository.mappings(9L, 1L)).thenReturn(List.of(new Mapping("temperature", "温度", "Plant.A.PV", "°C")));
        service = new IotQueryService(repository, provider, "Asia/Shanghai");
    }

    private Query query(String aggregation) {
        return new Query(List.of(1L), List.of("temperature"), start, start.plusMinutes(1), 1000L, aggregation);
    }

    @Test
    void realtimeUsesTenantMappingsAndPreservesUnknownQuality() throws Exception {
        when(client.getNamedData(anyList(), any())).thenReturn(mapper.readTree("{\"items\":[{\"name\":\"Plant.A.PV\",\"val\":12.75}]}"));
        QueryResult result = service.realtime(9, query("RAW"));
        assertThat(result.source()).isEqualTo("REALDB");
        assertThat(result.series().get(0).points().get(0).quality()).isEqualTo("UNKNOWN");
        assertThat(result.series().get(0).points().get(0).timeSource()).isEqualTo("RECEIVED");
        assertThat(result.series().get(0).points().get(0).value()).isEqualTo(new BigDecimal("12.75"));
        verify(repository).mappings(9L, 1L);
        verify(client).getNamedData(eq(List.of("Plant.A.PV")), any());
        verifyNoMoreInteractions(client);
    }

    @Test
    void absentRealtimePointIsMissingNotZero() throws Exception {
        when(client.getNamedData(anyList(), any())).thenReturn(mapper.readTree("{\"items\":[]}"));
        Point point = service.realtime(9, query("RAW")).series().get(0).points().get(0);
        assertThat(point.value()).isNull();
        assertThat(point.quality()).isEqualTo("MISSING");
        assertThat(point.timeSource()).isEqualTo("RECEIVED");
    }

    @Test
    void historyConvertsConfiguredLocalZoneAndAggregatesWithoutBadValues() throws Exception {
        when(client.getHistoryRange(any())).thenReturn(mapper.readTree("""
                {"items":[{"name":"Plant.A.PV","vals":[
                  {"time":"2026-10-26T10:00:00","val":10,"quality":"GOOD"},
                  {"time":"2026-10-26T10:00:01","val":90,"quality":"BAD"},
                  {"time":"2026-10-26T10:00:02","val":20,"quality":"GOOD"}]}]}
                """));
        Point point = service.history(9, query("AVG")).series().get(0).points().get(0);
        assertThat(point.value()).isEqualTo(new BigDecimal("15"));
        assertThat(point.quality()).isEqualTo("UNCERTAIN");
        assertThat(point.time()).isEqualTo("2026-10-26T02:00:02Z");
        assertThat(point.timeSource()).isEqualTo("DEVICE");
        ArgumentCaptor<RealDbHistoryQueryDTO> captor = ArgumentCaptor.forClass(RealDbHistoryQueryDTO.class);
        verify(client).getHistoryRange(captor.capture());
        assertThat(captor.getValue().getStartTime()).isEqualTo(start.toLocalDateTime());
        assertThat(captor.getValue().getOptions().getValueOnly()).isFalse();
    }

    @Test
    void absentHistoryIsEmptyRatherThanFabricatedSamples() throws Exception {
        when(client.getHistoryRange(any())).thenReturn(mapper.readTree("{\"items\":[]}"));
        assertThat(service.history(9, query("RAW")).series().get(0).points()).isEmpty();
    }

    @Test
    void suppliedRealtimeTimestampIsDistinguishedFromReceiptTime() throws Exception {
        when(client.getNamedData(anyList(),any())).thenReturn(mapper.readTree("{\"items\":[{\"name\":\"Plant.A.PV\",\"val\":12.75,\"time\":\"2026-10-26T10:00:00\",\"quality\":\"GOOD\"}]}"));
        Point point=service.realtime(9,query("RAW")).series().get(0).points().get(0);
        assertThat(point.timeSource()).isEqualTo("DEVICE");
        assertThat(point.time()).isEqualTo("2026-10-26T02:00:00Z");
        when(client.getNamedData(anyList(),any())).thenReturn(mapper.readTree("{\"items\":[{\"name\":\"Plant.A.PV\",\"val\":12.75,\"quality\":\"GOOD\"}]}"));
        Point received=service.realtime(9,query("RAW")).series().get(0).points().get(0);
        assertThat(received.timeSource()).isEqualTo("RECEIVED");
        assertThat(received.quality()).isEqualTo("GOOD"); // value quality does not imply known sample age.
    }

    @Test
    void disabledRealDbNeverFallsBackToMockData() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.realtime(9, query("RAW"))).isInstanceOf(ServiceException.class)
                .hasMessageContaining("不会回退演示数据");
    }

    @Test
    void missingMappingOrTenantFailsBeforeRemoteRequest() {
        assertThatThrownBy(() -> service.history(10, query("RAW"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.history(0, query("RAW"))).isInstanceOf(ServiceException.class);
        verifyNoInteractions(client);
    }

    @Test
    void rejectsDuplicateUnknownAndValueOnlyResponses() throws Exception {
        for (String body : List.of("{\"items\":[1]}",
                "{\"items\":[{\"name\":\"Secret.PV\",\"val\":1}]}",
                "{\"items\":[{\"name\":\"Plant.A.PV\",\"val\":1},{\"name\":\"Plant.A.PV\",\"val\":2}]}")) {
            when(client.getNamedData(anyList(), any())).thenReturn(mapper.readTree(body));
            assertThatThrownBy(() -> service.realtime(9, query("RAW"))).isInstanceOf(ServiceException.class);
        }
    }

    @Test
    void rejectsHistoryWithoutTimestampAndOutsideRequestedRange() throws Exception {
        for (String vals : List.of("[{\"val\":1}]", "[{\"time\":\"2026-10-25T10:00:00\",\"val\":1}]")) {
            when(client.getHistoryRange(any())).thenReturn(mapper.readTree("{\"items\":[{\"name\":\"Plant.A.PV\",\"vals\":" + vals + "}]}"));
            assertThatThrownBy(() -> service.history(9, query("RAW"))).isInstanceOf(ServiceException.class);
        }
    }

    @Test
    void enforcesWindowSampleAndDimensionLimits() {
        assertThatCode(() -> IotQueryService.validate(query("RAW"), true)).doesNotThrowAnyException();
        for (Query query : List.of(
                new Query(List.of(1L), List.of("temperature"), start, start.plusDays(8), 100_000L, "RAW"),
                new Query(List.of(1L), List.of("temperature"), start, start.plusDays(1), 1000L, "RAW"),
                new Query(List.of(1L), List.of("temperature"), start, start, 1000L, "RAW"),
                new Query(List.of(1L, 1L), List.of("temperature"), start, start.plusMinutes(1), 1000L, "RAW"),
                new Query(List.of(1L), List.of("SELECT *"), start, start.plusMinutes(1), 1000L, "RAW"),
                new Query(List.of(1L), List.of("temperature"), start, start.plusMinutes(1), 999L, "RAW"))) {
            assertThatThrownBy(() -> IotQueryService.validate(query, true)).isInstanceOf(ServiceException.class);
        }
    }

    @Test
    void handlesMissingUnknownAndNumericAggregationsHonestly() {
        List<Point> points = List.of(new Point("2026-01-01T00:00:00Z", new BigDecimal("2"), "UNKNOWN"),
                new Point("2026-01-01T00:00:01Z", new BigDecimal("4"), "GOOD"));
        assertThat(IotQueryService.aggregate(points, "AVG").get(0).value()).isEqualTo(new BigDecimal("3"));
        assertThat(IotQueryService.aggregate(points, "AVG").get(0).quality()).isEqualTo("UNKNOWN");
        assertThat(IotQueryService.aggregate(points, "MIN").get(0).value()).isEqualTo(new BigDecimal("2"));
        assertThat(IotQueryService.aggregate(points, "MAX").get(0).value()).isEqualTo(new BigDecimal("4"));
        assertThat(IotQueryService.aggregate(points, "LAST").get(0)).isEqualTo(points.get(1));
        assertThat(IotQueryService.aggregate(List.of(new Point("2026-01-01T00:00:00Z", null, "MISSING")), "AVG")
                .get(0).value()).isNull();
        assertThatThrownBy(() -> IotQueryService.aggregate(List.of(new Point("2026-01-01T00:00:00Z", "offline", "GOOD")), "AVG"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsDstAmbiguityBeforeReadingVendor() {
        IotQueryService dst = new IotQueryService(repository, provider, "America/New_York");
        OffsetDateTime ambiguous = OffsetDateTime.parse("2026-11-01T01:15:00-04:00");
        assertThatThrownBy(() -> dst.history(9, new Query(List.of(1L), List.of("temperature"), ambiguous,
                ambiguous.plusMinutes(1), 1000L, "RAW"))).isInstanceOf(ServiceException.class);
        verifyNoInteractions(client);
    }
}
