package com.security.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages kubectl operations through an SSH jumpbox — pod listing and command wrapping.
 */
@Service
public class KubectlService {

    private static final Logger log = LoggerFactory.getLogger(KubectlService.class);

    private final SshExecutionService sshService;
    private final ObjectMapper objectMapper;

    public KubectlService(SshExecutionService sshService, ObjectMapper objectMapper) {
        this.sshService = sshService;
        this.objectMapper = objectMapper;
    }

    /**
     * List pods on a Kubernetes cluster reachable from the jumpbox.
     * Connects via SSH to the jumpbox, runs "kubectl get pods -o json".
     */
    public List<PodInfo> listPods(String jumpboxIp, int jumpboxPort, String jumpboxUser,
                                  String jumpboxPassword, String namespace) {
        String nsFlag = (namespace != null && !namespace.isBlank()) ? " -n " + namespace : " --all-namespaces";
        String command = "kubectl get pods -o json" + nsFlag + " 2>&1";

        log.info("Kubectl: listing pods via jumpbox {}:{}", jumpboxIp, jumpboxPort);
        var result = sshService.executeRaw(jumpboxIp, jumpboxPort, jumpboxUser, jumpboxPassword, command, 30);

        if (result.getExitCode() != 0) {
            log.error("Kubectl pod listing failed: {}", result.getStderr());
            throw new RuntimeException("kubectl 命令执行失败: " + result.getStderr());
        }

        try {
            var json = objectMapper.readTree(result.getStdout());
            var items = json.get("items");
            if (items == null || !items.isArray()) {
                return Collections.emptyList();
            }

            List<PodInfo> pods = new ArrayList<>();
            for (var item : items) {
                var metadata = item.get("metadata");
                var spec = item.get("spec");
                var status = item.get("status");
                if (metadata == null) continue;

                String name = pathText(metadata, "name");
                String ns = pathText(metadata, "namespace");
                String phase = status != null ? pathText(status, "phase") : "Unknown";

                List<String> containers = new ArrayList<>();
                var containerArray = spec != null ? spec.get("containers") : null;
                if (containerArray != null && containerArray.isArray()) {
                    for (var c : containerArray) {
                        String cname = pathText(c, "name");
                        if (cname != null) containers.add(cname);
                    }
                }

                String nodeName = spec != null ? pathText(spec, "nodeName") : null;

                pods.add(new PodInfo(name, ns, phase, containers, nodeName));
            }

            log.info("Kubectl: found {} pods via jumpbox", pods.size());
            return pods;
        } catch (Exception e) {
            log.error("Failed to parse kubectl JSON output", e);
            throw new RuntimeException("kubectl 输出解析失败: " + e.getMessage());
        }
    }

    /**
     * Wrap a command for kubectl exec through the jumpbox.
     * Produces: kubectl exec -n <namespace> <pod> -- <originalCommand>
     */
    public String wrapCommand(String pod, String namespace, String originalCommand) {
        String ns = (namespace != null && !namespace.isBlank()) ? " -n " + namespace : "";
        return "kubectl exec" + ns + " " + pod + " -- " + originalCommand;
    }

    private String pathText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        var child = node.get(field);
        return child != null && !child.isNull() ? child.asText() : null;
    }

    /**
     * Simple POJO representing a Kubernetes pod.
     */
    public record PodInfo(String name, String namespace, String status,
                          List<String> containers, String nodeName) {

        public String displayName() {
            return (namespace != null ? namespace + "/" : "") + name;
        }
    }
}
