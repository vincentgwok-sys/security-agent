package com.security.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "security-agent.ssh")
public class SshPoolConfig {

    private int connectionTimeout = 10000;
    private int idleTimeout = 60000;
    private int maxSessionsPerHost = 3;

    public int getConnectionTimeout() { return connectionTimeout; }
    public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }

    public int getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(int idleTimeout) { this.idleTimeout = idleTimeout; }

    public int getMaxSessionsPerHost() { return maxSessionsPerHost; }
    public void setMaxSessionsPerHost(int maxSessionsPerHost) { this.maxSessionsPerHost = maxSessionsPerHost; }
}
