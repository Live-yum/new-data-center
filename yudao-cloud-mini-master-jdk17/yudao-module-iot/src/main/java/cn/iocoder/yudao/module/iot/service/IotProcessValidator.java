package cn.iocoder.yudao.module.iot.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.iot.model.IotModels.ProcessBinding;
import cn.iocoder.yudao.module.iot.model.IotModels.ProcessScene;

import java.util.HashSet;
import java.util.Set;

/** Fixed, versioned display layouts. Bindings never authorize device writes. */
public final class IotProcessValidator {
    private static final Set<String> TEMPLATES = Set.of("separation", "gas", "storage", "utilities", "offshore");
    private static final Set<String> SLOTS = Set.of("slot-1", "slot-2", "slot-3", "slot-4");
    private IotProcessValidator() {}

    public static void validate(ProcessScene scene) {
        if (scene == null || scene.name() == null || scene.name().isBlank() || scene.name().length() > 100
                || (scene.description() != null && scene.description().length() > 500)
                || scene.version() < 0 || scene.schemaVersion() != 1
                || scene.template() == null || !TEMPLATES.contains(scene.template())
                || scene.bindings() == null || scene.bindings().size() != 4
                || scene.refreshSeconds() < 5 || scene.refreshSeconds() > 300
                || scene.staleAfterSeconds() < scene.refreshSeconds() || scene.staleAfterSeconds() > 3600) {
            throw invalid("工艺页面名称、模板、版本、刷新或过期参数无效");
        }
        Set<String> slots = new HashSet<>();
        int series = 0;
        for (ProcessBinding binding : scene.bindings()) {
            if (binding == null || binding.slotId() == null || !SLOTS.contains(binding.slotId())
                    || !slots.add(binding.slotId()) || binding.metricKeys() == null
                    || binding.primaryMetricKey() == null) throw invalid("必须配置四个不同的工艺设备位置");
            if (binding.deviceId() == null) {
                if (!binding.metricKeys().isEmpty() || !binding.primaryMetricKey().isEmpty())
                    throw invalid("未绑定设备的位置不能配置指标");
                continue;
            }
            if (binding.deviceId() <= 0 || binding.metricKeys().isEmpty() || binding.metricKeys().size() > 20
                    || binding.metricKeys().stream().anyMatch(k -> k == null || !k.matches("[A-Za-z0-9_.-]{1,64}"))
                    || new HashSet<>(binding.metricKeys()).size() != binding.metricKeys().size()
                    || !binding.metricKeys().contains(binding.primaryMetricKey())) {
                throw invalid("已绑定设备必须选择1至20个不同指标，主指标须在所选指标中");
            }
            series += binding.metricKeys().size();
        }
        if (series > 50) throw invalid("工艺页面最多绑定50个设备指标序列");
    }

    static ServiceException invalid(String message) { return new ServiceException(1_070_000_400, message); }
}
