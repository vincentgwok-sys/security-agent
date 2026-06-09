package com.security.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 路径解析工具 — 将配置中的相对路径基于 user.dir 转换为绝对路径。
 */
public class PathResolver {

    private static final Logger log = LoggerFactory.getLogger(PathResolver.class);

    private PathResolver() {}

    /**
     * 解析配置路径。若为绝对路径则原样返回，否则拼接 user.dir。
     */
    public static Path resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            log.warn("PathResolver: 配置路径为空，回退到 user.dir");
            return Path.of(System.getProperty("user.dir", "."));
        }
        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path;
        }
        Path resolved = Path.of(System.getProperty("user.dir", ".")).resolve(path).normalize();
        log.debug("PathResolver: {} -> {}", configured, resolved);
        return resolved;
    }

    /**
     * 解析配置路径并返回字符串形式。
     */
    public static String resolveToString(String configured) {
        return resolve(configured).toString();
    }
}
