package com.security.agent.service;

import com.security.agent.model.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 本地命令执行服务 — 通过 ProcessBuilder 在本机执行命令。
 * 用于 connectionType=local 的检测任务，无需 SSH。
 */
@Service
public class LocalExecutionService {

    private static final Logger log = LoggerFactory.getLogger(LocalExecutionService.class);

    /**
     * 在本机执行命令，返回 ExecutionResult。
     *
     * @param command        要执行的 shell 命令
     * @param timeoutSeconds 命令超时（秒）
     * @return ExecutionResult 包含 stdout、stderr、exitCode
     */
    public ExecutionResult execute(String command, int timeoutSeconds) {
        log.debug("本地执行命令 (timeout={}s): {}", timeoutSeconds, command);

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();

            Thread stdoutThread = transferAsync(process.getInputStream(), stdoutBuf);
            Thread stderrThread = transferAsync(process.getErrorStream(), stderrBuf);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            int exitCode;
            if (finished) {
                exitCode = process.exitValue();
            } else {
                log.warn("命令超时，强制终止: {}", command);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                exitCode = -1;
            }

            stdoutThread.join(2000);
            stderrThread.join(2000);

            return ExecutionResult.builder()
                    .stdout(stdoutBuf.toString(StandardCharsets.UTF_8))
                    .stderr(stderrBuf.toString(StandardCharsets.UTF_8))
                    .exitCode(exitCode)
                    .blocked(false)
                    .connectionError(false)
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("本地命令执行被中断: {}", command);
            return ExecutionResult.builder()
                    .stdout("")
                    .stderr("命令执行被中断: " + e.getMessage())
                    .exitCode(-1)
                    .blocked(false)
                    .connectionError(false)
                    .build();
        } catch (IOException e) {
            log.error("本地命令执行失败: {}", command, e);
            return ExecutionResult.builder()
                    .stdout("")
                    .stderr("命令执行异常: " + e.getMessage())
                    .exitCode(-1)
                    .blocked(false)
                    .connectionError(true)
                    .build();
        }
    }

    /**
     * 原始执行 — 与 execute 相同，规则引擎不适用于本地模式。
     */
    public ExecutionResult executeRaw(String command, int timeoutSeconds) {
        return execute(command, timeoutSeconds);
    }

    private Thread transferAsync(java.io.InputStream in, java.io.OutputStream out) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } catch (IOException ignored) {
                // Stream closed — expected after process termination
            }
        }, "local-exec-stream");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
