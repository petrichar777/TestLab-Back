package com.exam.service;

import com.exam.exception.BusinessException;
import com.exam.model.entity.AiConfig;
import com.exam.model.entity.AiModel;
import com.exam.model.entity.AiProvider;
import com.exam.repository.AiConfigRepository;
import com.exam.repository.AiModelRepository;
import com.exam.repository.AiProviderRepository;
import com.exam.security.SecurityUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AiSettingsService {

    private static final Long CONFIG_ID = 1L;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final AiConfigRepository configRepo;
    private final AiProviderRepository providerRepo;
    private final AiModelRepository modelRepo;

    public AiSettingsService(AiConfigRepository configRepo, AiProviderRepository providerRepo, AiModelRepository modelRepo) {
        this.configRepo = configRepo;
        this.providerRepo = providerRepo;
        this.modelRepo = modelRepo;
    }
    private static final Map<String, List<String>> DEFAULT_CATALOG = new LinkedHashMap<>();
    static {
        DEFAULT_CATALOG.put("deepseek", List.of("https://api.deepseek.com", "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp"));
        DEFAULT_CATALOG.put("aliyun", List.of("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.8-max", "qwen3.7-plus", "qwen3.8-flash", "qwen-max", "qwen-plus"));
        DEFAULT_CATALOG.put("zhipu", List.of("https://open.bigmodel.cn/api/paas/v4", "glm-5.3", "glm-5.3-flash", "glm-5.2", "glm-4.7-flash"));
        DEFAULT_CATALOG.put("moonshot", List.of("https://api.moonshot.cn/v1", "kimi-k3", "kimi-k2.7-code", "kimi-k2.6", "moonshot-v1-auto"));
        DEFAULT_CATALOG.put("iflytek", List.of("https://spark-api-open.xf-yun.com/v1", "spark-max", "spark-pro", "spark-lite"));
        DEFAULT_CATALOG.put("baichuan", List.of("https://api.baichuan-ai.com/v1", "Baichuan4", "Baichuan4-Turbo"));
        DEFAULT_CATALOG.put("stepfun", List.of("https://api.stepfun.com/v1", "step-3.5-flash", "step-3", "step-2-mini"));
        DEFAULT_CATALOG.put("minimax", List.of("https://api.minimax.chat/v1", "MiniMax-Text-01", "MiniMax-M3", "abab6.5s-chat"));
        DEFAULT_CATALOG.put("ernie", List.of("https://qianfan.baidubce.com/v2", "ernie-4.0-8k", "ernie-4.0-turbo-8k", "ernie-x1"));
        DEFAULT_CATALOG.put("doubao", List.of("https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2.1-pro", "doubao-1.5-pro-32k", "doubao-1.5-lite-32k"));
    }

    @PostConstruct
    @Transactional
    public void seedIfEmpty() {
        if (providerRepo.count() > 0) return;
        int ps = 0;
        for (Map.Entry<String, List<String>> e : DEFAULT_CATALOG.entrySet()) {
            List<String> v = e.getValue();
            AiProvider provider = new AiProvider();
            provider.setCode(e.getKey());
            provider.setName(displayName(e.getKey()));
            provider.setBaseUrl(v.get(0));
            provider.setSortOrder(ps++);
            provider.setEnabled(true);
            provider.setUpdatedAt(LocalDateTime.now());
            provider = providerRepo.save(provider);
            int mi = 0;
            final AiProvider fp = provider;
            for (int i = 1; i < v.size(); i++) {
                AiModel mo = new AiModel();
                mo.setProvider(fp);
                mo.setName(v.get(i));
                mo.setSortOrder(mi++);
                mo.setHot(i <= 2);
                mo.setEnabled(true);
                mo.setUpdatedAt(LocalDateTime.now());
                modelRepo.save(mo);
            }
        }
        AiConfig cfg = configRepo.findById(CONFIG_ID).orElseGet(() -> {
            AiConfig c = new AiConfig();
            c.setId(CONFIG_ID);
            c.setUpdatedAt(LocalDateTime.now());
            return c;
        });
        if (cfg.getProvider() == null) cfg.setProvider("deepseek");
        if (cfg.getModel() == null) cfg.setModel("deepseek-v4-pro");
        configRepo.save(cfg);
    }

    private String displayName(String code) {
        return switch (code) {
            case "deepseek" -> "DeepSeek（深度求索）";
            case "aliyun" -> "通义千问（阿里云百炼）";
            case "zhipu" -> "智谱 GLM";
            case "moonshot" -> "Kimi（月之暗面）";
            case "iflytek" -> "讯飞星火";
            case "baichuan" -> "百川大模型";
            case "stepfun" -> "阶跃星辰 StepFun";
            case "minimax" -> "MiniMax";
            case "ernie" -> "百度文心（千帆）";
            case "doubao" -> "字节豆包（火山方舟）";
            default -> code;
        };
    }

    public List<Map<String, Object>> modelCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiProvider provider : providerRepo.findAllByOrderBySortOrderAscIdAsc()) {
            if (!Boolean.TRUE.equals(provider.getEnabled())) continue;
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", provider.getCode());
            pm.put("name", provider.getName());
            pm.put("baseUrl", provider.getBaseUrl() != null ? provider.getBaseUrl() : "");
            List<Map<String, Object>> models = new ArrayList<>();
            for (AiModel mo : modelRepo.findByProviderOrderBySortOrderAscIdAsc(provider)) {
                if (!Boolean.TRUE.equals(mo.getEnabled())) continue;
                models.add(Map.of("name", mo.getName(), "hot", Boolean.TRUE.equals(mo.getHot())));
            }
            pm.put("models", models);
            out.add(pm);
        }
        return out;
    }

    public List<Map<String, Object>> catalogAdmin() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiProvider provider : providerRepo.findAllByOrderBySortOrderAscIdAsc()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", provider.getId());
            pm.put("code", provider.getCode());
            pm.put("name", provider.getName());
            pm.put("baseUrl", provider.getBaseUrl() != null ? provider.getBaseUrl() : "");
            pm.put("sortOrder", provider.getSortOrder());
            pm.put("enabled", Boolean.TRUE.equals(provider.getEnabled()));
            List<Map<String, Object>> models = new ArrayList<>();
            for (AiModel mo : modelRepo.findByProviderOrderBySortOrderAscIdAsc(provider)) {
                models.add(Map.of("id", mo.getId(), "name", mo.getName(), "hot", Boolean.TRUE.equals(mo.getHot()), "enabled", Boolean.TRUE.equals(mo.getEnabled())));
            }
            pm.put("models", models);
            out.add(pm);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createProvider(String code, String name, String baseUrl) {
        if (code == null || code.isBlank()) throw new BusinessException(422, "供应商标识(code)不能为空");
        if (name == null || name.isBlank()) throw new BusinessException(422, "供应商名称不能为空");
        if (providerRepo.existsByCode(code.trim())) throw new BusinessException(422, "供应商标识已存在: " + code);
        AiProvider provider = new AiProvider();
        provider.setCode(code.trim());
        provider.setName(name.trim());
        provider.setBaseUrl(baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim());
        provider.setSortOrder((int) providerRepo.count());
        provider.setEnabled(true);
        provider.setUpdatedAt(LocalDateTime.now());
        providerRepo.save(provider);
        return Map.of("ok", true, "id", provider.getId());
    }

    @Transactional
    public Map<String, Object> updateProvider(Long id, String name, String baseUrl, Integer sortOrder, Boolean enabled) {
        AiProvider provider = providerRepo.findById(id).orElseThrow(() -> new BusinessException(404, "provider not found"));
        if (name != null && !name.isBlank()) provider.setName(name.trim());
        if (baseUrl != null) provider.setBaseUrl(baseUrl.isBlank() ? null : baseUrl.trim());
        if (sortOrder != null) provider.setSortOrder(sortOrder);
        if (enabled != null) provider.setEnabled(enabled);
        provider.setUpdatedAt(LocalDateTime.now());
        providerRepo.save(provider);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> deleteProvider(Long id) {
        AiProvider provider = providerRepo.findById(id).orElseThrow(() -> new BusinessException(404, "provider not found"));
        modelRepo.deleteByProvider(provider);
        providerRepo.delete(provider);
        AiConfig cfg = configRepo.findById(CONFIG_ID).orElse(null);
        if (cfg != null && Objects.equals(cfg.getProvider(), provider.getCode())) {
            cfg.setProvider("deepseek");
            cfg.setModel("deepseek-v4-pro");
            configRepo.save(cfg);
        }
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> createModel(Long providerId, String name, Boolean hot) {
        AiProvider provider = providerRepo.findById(providerId).orElseThrow(() -> new BusinessException(404, "provider not found"));
        if (name == null || name.isBlank()) throw new BusinessException(422, "模型名不能为空");
        List<AiModel> exist = modelRepo.findByProviderOrderBySortOrderAscIdAsc(provider);
        if (exist.stream().anyMatch(m -> m.getName().equals(name.trim()))) throw new BusinessException(422, "该供应商下已有此模型: " + name);
        AiModel mo = new AiModel();
        mo.setProvider(provider);
        mo.setName(name.trim());
        mo.setSortOrder(exist.size());
        mo.setHot(Boolean.TRUE.equals(hot));
        mo.setEnabled(true);
        mo.setUpdatedAt(LocalDateTime.now());
        modelRepo.save(mo);
        return Map.of("ok", true, "id", mo.getId());
    }

    @Transactional
    public Map<String, Object> updateModel(Long id, String name, Boolean hot, Boolean enabled) {
        AiModel mo = modelRepo.findById(id).orElseThrow(() -> new BusinessException(404, "model not found"));
        if (name != null && !name.isBlank()) mo.setName(name.trim());
        if (hot != null) mo.setHot(hot);
        if (enabled != null) mo.setEnabled(enabled);
        mo.setUpdatedAt(LocalDateTime.now());
        modelRepo.save(mo);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> deleteModel(Long id) {
        modelRepo.findById(id).orElseThrow(() -> new BusinessException(404, "model not found"));
        modelRepo.deleteById(id);
        return Map.of("ok", true);
    }

    public Map<String, Object> getConfig() {
        AiConfig cfg = current();
        boolean hasKey = cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("provider", cfg.getProvider());
        resp.put("model", cfg.getModel());
        resp.put("baseUrl", cfg.getBaseUrl() != null ? cfg.getBaseUrl() : "");
        resp.put("hasKey", hasKey);
        resp.put("apiKeyMasked", hasKey ? mask(cfg.getApiKey()) : "");
        resp.put("updatedAt", cfg.getUpdatedAt());
        return resp;
    }

    public Map<String, Object> saveConfig(String provider, String model, String apiKey, String baseUrl) {
        if (provider == null || provider.isBlank()) throw new BusinessException(422, "please select a provider");
        if (model == null || model.isBlank()) throw new BusinessException(422, "please select a model");
        AiConfig cfg = current();
        cfg.setProvider(provider);
        cfg.setModel(model);
        if (apiKey != null && !apiKey.isBlank()) cfg.setApiKey(apiKey.trim());
        if (baseUrl != null && !baseUrl.isBlank()) cfg.setBaseUrl(baseUrl.trim());
        cfg.setUpdatedBy(SecurityUtils.currentUsername());
        cfg.setUpdatedAt(LocalDateTime.now());
        configRepo.save(cfg);
        return getConfig();
    }

    public Map<String, Object> testConnection(String provider, String model, String apiKey, String baseUrl) {
        String key = (apiKey != null && !apiKey.isBlank()) ? apiKey.trim() : current().getApiKey();
        if (key == null || key.isBlank()) throw new BusinessException(422, "API Key 未填写");

        String url = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : defaultBaseUrl(provider);
        if (url == null || url.isBlank()) throw new BusinessException(422, "请选择供应商以获取接口地址");

        String body = toJson(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", 4
        ));
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ensureChatUrl(url)))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 300) {
                return Map.of("ok", false, "message", "HTTP " + resp.statusCode() + ": " + snippet(resp.body(), 200));
            }
            Map<String, Object> parsed = (Map<String, Object>) MAPPER.readValue(resp.body(), Map.class);
            Object choices = parsed.get("choices");
            boolean ok = choices instanceof List<?> l && !l.isEmpty();
            return Map.of("ok", ok, "message", ok ? "连接成功" : "响应中未包含有效结果");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    private AiConfig current() {
        return configRepo.findById(CONFIG_ID).orElseGet(() -> {
            AiConfig c = new AiConfig();
            c.setId(CONFIG_ID);
            c.setProvider("deepseek");
            c.setModel("deepseek-v4-pro");
            c.setUpdatedAt(LocalDateTime.now());
            return configRepo.save(c);
        });
    }

    private String defaultBaseUrl(String provider) {
        for (Map<String, Object> p : modelCatalog()) {
            if (provider.equals(p.get("id"))) return (String) p.get("baseUrl");
        }
        throw new BusinessException(422, "unknown provider: " + provider);
    }

    private String ensureChatUrl(String url) {
        if (url.endsWith("/chat/completions")) return url;
        return url + (url.endsWith("/") ? "" : "/") + "chat/completions";
    }

    private String mask(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private String snippet(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
