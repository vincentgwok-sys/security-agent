package com.security.agent.service;

import com.security.agent.config.SshPoolConfig;
import com.security.agent.model.ExecutionResult;
import com.security.agent.model.RuleVerdict;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class SshExecutionService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SshExecutionService.class);

    private final SshPoolConfig config;
    private final CommandRuleService ruleService;
    private final SshClient sshClient;
    private final Map<String, ClientSession> sessionCache = new ConcurrentHashMap<>();

    public SshExecutionService(SshPoolConfig config, CommandRuleService ruleService) {
        this.config = config;
        this.ruleService = ruleService;
        this.sshClient = SshClient.setUpDefaultClient();
        this.sshClient.start();
    }

    public ExecutionResult execute(String host, int port, String user, String password,
                                    String command, int timeoutSeconds) {
        log.debug("SSH 执行命令 [{}]: {}", host, command);

        // 先经规则引擎校验
        RuleVerdict verdict = ruleService.filter(command, "", "");
        if ("BLOCK".equals(verdict.getVerdict())) {
            log.warn("SSH 命令被规则拦截 [{}]: {} — {}", host, command, verdict.getMessage());
            return ExecutionResult.builder()
                    .stdout("")
                    .stderr(verdict.getMessage())
                    .exitCode(-1)
                    .blocked(true)
                    .build();
        }

        ClientSession session = null;
        try {
            session = getOrCreateSession(host, port, user, password);

            try (ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                 ByteArrayOutputStream stderr = new ByteArrayOutputStream()) {

                ClientChannel channel = session.createExecChannel(command);
                channel.setOut(stdout);
                channel.setErr(stderr);

                channel.open().verify(timeoutSeconds, TimeUnit.SECONDS);
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.SECONDS.toMillis(timeoutSeconds));

                Integer exitCode = channel.getExitStatus();
                channel.close();

                return ExecutionResult.builder()
                        .stdout(stdout.toString(StandardCharsets.UTF_8))
                        .stderr(stderr.toString(StandardCharsets.UTF_8))
                        .exitCode(exitCode != null ? exitCode : -1)
                        .blocked(false)
                        .build();
            }
        } catch (Exception e) {
            log.error("SSH 执行失败 [{}]: {}", host, command, e);
            return ExecutionResult.builder()
                    .stdout("")
                    .stderr("SSH 执行异常: " + e.getMessage())
                    .exitCode(-1)
                    .blocked(false)
                    .build();
        }
        // Session is NOT closed here — it's pooled for reuse
    }

    private ClientSession getOrCreateSession(String host, int port, String user, String password) throws IOException {
        String key = host + ":" + port + ":" + user;
        ClientSession session = sessionCache.get(key);

        if (session == null || !session.isOpen() || session.isClosed() || session.isClosing()) {
            // Evict stale session
            if (session != null) {
                sessionCache.remove(key);
                closeQuietly(session);
            }

            // Check session cap for this host
            String hostKey = host + ":" + port;
            long hostSessionCount = sessionCache.keySet().stream()
                    .filter(k -> k.startsWith(hostKey))
                    .count();

            if (hostSessionCount >= config.getMaxSessionsPerHost()) {
                // Release the oldest session for this host
                sessionCache.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(hostKey))
                        .findFirst()
                        .ifPresent(e -> {
                            closeQuietly(e.getValue());
                            sessionCache.remove(e.getKey());
                        });
            }

            session = sshClient.connect(user, host, port)
                    .verify(config.getConnectionTimeout(), TimeUnit.MILLISECONDS)
                    .getSession();
            session.addPasswordIdentity(password);
            session.auth().verify(config.getConnectionTimeout(), TimeUnit.MILLISECONDS);

            sessionCache.put(key, session);
            log.info("SSH 新建连接: {}:{}@{}", user, host, port);
        }

        return session;
    }

    public ExecutionResult executeRaw(String host, int port, String user, String password,
                                       String command, int timeoutSeconds) {
        // Bypass rule engine — used for env fingerprint probes
        ClientSession session = null;
        try {
            session = getOrCreateSession(host, port, user, password);

            try (ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                 ByteArrayOutputStream stderr = new ByteArrayOutputStream()) {

                ClientChannel channel = session.createExecChannel(command);
                channel.setOut(stdout);
                channel.setErr(stderr);

                channel.open().verify(timeoutSeconds, TimeUnit.SECONDS);
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.SECONDS.toMillis(timeoutSeconds));

                Integer exitCode = channel.getExitStatus();
                channel.close();

                return ExecutionResult.builder()
                        .stdout(stdout.toString(StandardCharsets.UTF_8))
                        .stderr(stderr.toString(StandardCharsets.UTF_8))
                        .exitCode(exitCode != null ? exitCode : -1)
                        .build();
            }
        } catch (Exception e) {
            log.error("SSH 原始执行失败 [{}]: {}", host, command, e);
            return ExecutionResult.builder()
                    .stdout("")
                    .stderr("SSH 执行异常: " + e.getMessage())
                    .exitCode(-1)
                    .build();
        }
    }

    public void release(String hostPort) {
        sessionCache.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(hostPort)) {
                closeQuietly(entry.getValue());
                return true;
            }
            return false;
        });
        log.info("SSH 连接已释放: {}", hostPort);
    }

    private void closeQuietly(ClientSession session) {
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } catch (IOException e) {
            log.debug("关闭 SSH Session 异常", e);
        }
    }

    @Override
    public void close() {
        sessionCache.values().forEach(this::closeQuietly);
        sessionCache.clear();
        if (sshClient != null) {
            sshClient.stop();
        }
        log.info("SSH 连接池已关闭");
    }
}
