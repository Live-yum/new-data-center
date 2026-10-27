package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.module.iot.model.IotModels.Mapping;
import cn.iocoder.yudao.module.iot.model.IotModels.ProcessBinding;
import cn.iocoder.yudao.module.iot.model.IotModels.ProcessScene;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Process configuration is relationally persisted and always scoped to the verified tenant. */
@Repository
public class IotProcessRepository {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final IotRepository devices;
    private static final String COLUMNS = "id,name,description,version,schema_version,template,bindings,refresh_seconds,stale_after_seconds";

    public IotProcessRepository(JdbcTemplate db, ObjectMapper json, IotRepository devices) {
        this.db = db; this.json = json; this.devices = devices;
    }

    public List<ProcessScene> list(long tenant) {
        requireTenant(tenant);
        return db.query("SELECT " + COLUMNS + " FROM iot_process_page WHERE tenant_id=? ORDER BY id LIMIT 100",
                (rs, n) -> read(rs), tenant);
    }

    public ProcessScene get(long tenant, long id) {
        requireTenant(tenant);
        return db.query("SELECT " + COLUMNS + " FROM iot_process_page WHERE tenant_id=? AND id=?",
                (rs, n) -> read(rs), tenant, id).stream().findFirst().orElseThrow(IotRepository::missing);
    }

    @Transactional
    public ProcessScene save(long tenant, Long id, ProcessScene body) {
        requireTenant(tenant);
        IotProcessValidator.validate(body);
        if (id == null) {
            if (body.version() != 0) throw IotRepository.conflict();
            if (body.id() != null && body.id() != 0) throw IotProcessValidator.invalid("新工艺页面不能指定已有ID");
        } else {
            get(tenant, id);
            if (body.id() != null && !body.id().equals(id)) throw IotProcessValidator.invalid("工艺页面ID与路径不一致");
        }
        Map<Long, Set<String>> available = new HashMap<>();
        for (ProcessBinding binding : body.bindings()) {
            if (binding.deviceId() == null) continue;
            Set<String> keys = available.computeIfAbsent(binding.deviceId(), device ->
                    devices.mappings(tenant, device).stream().map(Mapping::metricKey).collect(Collectors.toSet()));
            if (!keys.containsAll(binding.metricKeys())) throw IotProcessValidator.invalid("设备未配置工艺页面所选的统一指标映射");
        }
        String description = body.description() == null ? "" : body.description();
        if (id == null) {
            // Cross-instance admission prevents a saved 101st page from vanishing from the bounded list.
            db.update("INSERT INTO iot_process_tenant(tenant_id) VALUES(?) ON CONFLICT DO NOTHING", tenant);
            db.queryForObject("SELECT tenant_id FROM iot_process_tenant WHERE tenant_id=? FOR UPDATE", Long.class, tenant);
            Integer count = db.queryForObject("SELECT count(*) FROM iot_process_page WHERE tenant_id=?", Integer.class, tenant);
            if (count != null && count >= 100) throw IotProcessValidator.invalid("当前租户最多100个工艺页面，请先删除不再使用的页面");
            id = db.queryForObject("SELECT nextval('iot_process_page_seq')", Long.class);
            db.update("INSERT INTO iot_process_page(id,tenant_id,name,description,version,schema_version,template,bindings,refresh_seconds,stale_after_seconds) VALUES(?,?,?,?,0,?,?,?,?,?)",
                    id, tenant, body.name(), description, body.schemaVersion(), body.template(), devices.encode(body.bindings()), body.refreshSeconds(), body.staleAfterSeconds());
        } else if (db.update("UPDATE iot_process_page SET name=?,description=?,version=version+1,schema_version=?,template=?,bindings=?,refresh_seconds=?,stale_after_seconds=? WHERE tenant_id=? AND id=? AND version=?",
                body.name(), description, body.schemaVersion(), body.template(), devices.encode(body.bindings()), body.refreshSeconds(), body.staleAfterSeconds(), tenant, id, body.version()) != 1) {
            throw IotRepository.conflict();
        }
        return get(tenant, id);
    }

    public boolean delete(long tenant, long id, long version) {
        get(tenant, id);
        if (db.update("DELETE FROM iot_process_page WHERE tenant_id=? AND id=? AND version=?", tenant, id, version) != 1)
            throw IotRepository.conflict();
        return true;
    }

    private ProcessScene read(ResultSet rs) throws SQLException {
        try {
            return new ProcessScene(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getLong("version"),
                    rs.getInt("schema_version"), rs.getString("template"), json.readValue(rs.getString("bindings"), new TypeReference<List<ProcessBinding>>() {}),
                    rs.getInt("refresh_seconds"), rs.getInt("stale_after_seconds"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Invalid stored process-page configuration", failure);
        }
    }

    private static void requireTenant(long tenant) {
        if (tenant <= 0) throw IotProcessValidator.invalid("租户上下文缺失");
    }
}
