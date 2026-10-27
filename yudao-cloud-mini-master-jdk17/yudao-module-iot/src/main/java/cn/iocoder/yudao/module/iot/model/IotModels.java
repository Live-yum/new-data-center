package cn.iocoder.yudao.module.iot.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

/** Versioned HTTP contract. IDs and tenant/owner are never taken from request identity headers. */
public final class IotModels {
 private IotModels() {}
 public record Device(Long id, String name, String code) {}
 public record DeviceInput(@NotBlank @Size(max=100) String name,
                           @NotBlank @Pattern(regexp="[A-Za-z0-9_.-]{1,64}") String code) {}
 public record Mapping(@NotBlank @Pattern(regexp="[A-Za-z0-9_.-]{1,64}") String metricKey,
                       @NotBlank @Size(max=100) String name,
                       @NotBlank @Size(max=256) String pointName, @NotNull @Size(max=32) String unit) {}
 public record Binding(@NotBlank String mode, @NotNull List<Long> deviceIds, @NotBlank String metricKey) {}
 public record Grid(int x, int y, int w, int h) {}
 public record Hotspot(double x, double y, String label, String metricKey) {}
 public record Widget(@NotBlank String id, @NotBlank @Size(max=100) String title,
                      @NotBlank String type, @NotNull @Valid Binding binding, @NotNull Grid grid,
                      String unit, int timeWindowSeconds, String aggregation,
                      Double min, Double max, String imageUrl, List<Hotspot> hotspots) {}
 public record Dashboard(Long id, @NotBlank @Size(max=100) String name, long version,
                         int schemaVersion, @NotNull @Size(max=50) List<@Valid Widget> widgets) {}
 public record Query(List<Long> deviceIds, List<String> metricKeys, OffsetDateTime startTime,
                     OffsetDateTime endTime, Long intervalMs, String aggregation) {}
 public record Point(String time, Object value, String quality, String timeSource) {
  /** Existing internal history/fixture callers supply actual observation timestamps. */
  public Point(String time, Object value, String quality) { this(time,value,quality,"DEVICE"); }
 }
 public record Series(Long deviceId, String metricKey, String unit, List<Point> points) {}
 public record QueryResult(String source, List<Series> series) {}
 public record ExportJob(String id, String status, String createdAt, String expiresAt, String error) {}
 /** Deployment-owned connection metadata only: never a URL, token or credential. */
 public record Source(String id, String name, String type, boolean enabled, boolean configured,
                      String status, String timeZone, String description) {}
 public record ProcessBinding(String slotId, Long deviceId, List<String> metricKeys, String primaryMetricKey) {}
 public record ProcessScene(Long id, String name, String description, long version, int schemaVersion,
                            String template, List<ProcessBinding> bindings,
                            int refreshSeconds, int staleAfterSeconds) {}
}
