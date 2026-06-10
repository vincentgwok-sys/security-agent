package com.security.agent.controller;

import com.security.agent.service.KubectlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kubectl")
public class KubectlController {

    private static final Logger log = LoggerFactory.getLogger(KubectlController.class);

    private final KubectlService kubectlService;

    public KubectlController(KubectlService kubectlService) {
        this.kubectlService = kubectlService;
    }

    @PostMapping("/pods")
    public ResponseEntity<?> listPods(@Valid @RequestBody PodListRequest request) {
        log.info("Kubectl: listing pods via jumpbox {}:{}", request.jumpboxIp(), request.jumpboxPort());

        try {
            List<KubectlService.PodInfo> pods = kubectlService.listPods(
                    request.jumpboxIp(),
                    request.jumpboxPort() != null ? request.jumpboxPort() : 22,
                    request.jumpboxUser(),
                    request.jumpboxPassword(),
                    request.namespace());

            return ResponseEntity.ok(Map.of(
                    "pods", pods,
                    "total", pods.size()
            ));
        } catch (Exception e) {
            log.error("Kubectl pod list failed", e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "获取 Pod 列表失败: " + e.getMessage()));
        }
    }

    public record PodListRequest(
            @NotBlank String jumpboxIp,
            Integer jumpboxPort,
            @NotBlank String jumpboxUser,
            @NotBlank String jumpboxPassword,
            String namespace) {}
}
