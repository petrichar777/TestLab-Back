package com.exam.controller;

import com.exam.service.AiSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ai")
public class AiSettingsController {

    private final AiSettingsService aiSettingsService;

    public AiSettingsController(AiSettingsService aiSettingsService) {
        this.aiSettingsService = aiSettingsService;
    }

    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> models() {
        return ResponseEntity.ok(aiSettingsService.modelCatalog());
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(aiSettingsService.getConfig());
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(aiSettingsService.saveConfig(
                body.get("provider"), body.get("model"), body.get("apiKey"), body.get("baseUrl")));
    }

    @PostMapping("/config/test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(aiSettingsService.testConnection(
                body.get("provider"), body.get("model"), body.get("apiKey"), body.get("baseUrl")));
    }

    // ================= 目录管理（后台可维护） =================

    // 管理端完整目录（含禁用项 + 内部 id）
    @GetMapping("/catalog")
    public ResponseEntity<List<Map<String, Object>>> catalog() {
        return ResponseEntity.ok(aiSettingsService.catalogAdmin());
    }

    @PostMapping("/providers")
    public ResponseEntity<Map<String, Object>> createProvider(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(aiSettingsService.createProvider(body.get("code"), body.get("name"), body.get("baseUrl")));
    }

    @PutMapping("/providers/{id}")
    public ResponseEntity<Map<String, Object>> updateProvider(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(aiSettingsService.updateProvider(id,
                asString(body.get("name")), asString(body.get("baseUrl")),
                toInt(body.get("sortOrder")), toBool(body.get("enabled"))));
    }

    @DeleteMapping("/providers/{id}")
    public ResponseEntity<Map<String, Object>> deleteProvider(@PathVariable("id") Long id) {
        return ResponseEntity.ok(aiSettingsService.deleteProvider(id));
    }

    @PostMapping("/providers/{providerId}/models")
    public ResponseEntity<Map<String, Object>> createModel(@PathVariable("providerId") Long providerId, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(aiSettingsService.createModel(providerId, asString(body.get("name")), toBool(body.get("hot"))));
    }

    @PutMapping("/models/{id}")
    public ResponseEntity<Map<String, Object>> updateModel(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(aiSettingsService.updateModel(id, asString(body.get("name")), toBool(body.get("hot")), toBool(body.get("enabled"))));
    }

    @DeleteMapping("/models/{id}")
    public ResponseEntity<Map<String, Object>> deleteModel(@PathVariable("id") Long id) {
        return ResponseEntity.ok(aiSettingsService.deleteModel(id));
    }

    private String asString(Object o) { return o == null ? null : o.toString(); }
    private Integer toInt(Object o) { return o instanceof Number ? ((Number) o).intValue() : null; }
    private Boolean toBool(Object o) { return o == null ? null : Boolean.valueOf(o.toString()); }
}