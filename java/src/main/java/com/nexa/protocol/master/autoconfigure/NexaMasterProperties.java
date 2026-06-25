package com.nexa.protocol.master.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "nexa.master")
public class NexaMasterProperties {

    /** 是否启用 Master 节点 */
    private boolean enabled = true;
    /** 绑定地址 */
    private String host = "0.0.0.0";
    /** 监听端口 */
    private int port = 9090;
    /** 单帧最大字节数，默认 10MB */
    private int maxFrameSize = 10 * 1024 * 1024;
    /** 心跳超时时间，超过该时间未收到心跳则判定 Runner 离线 */
    private Duration heartbeatTimeout = Duration.ofSeconds(30);
    /** 心跳检查间隔 */
    private Duration heartbeatCheckInterval = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    public void setMaxFrameSize(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    public Duration getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    public void setHeartbeatTimeout(Duration heartbeatTimeout) {
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public Duration getHeartbeatCheckInterval() {
        return heartbeatCheckInterval;
    }

    public void setHeartbeatCheckInterval(Duration heartbeatCheckInterval) {
        this.heartbeatCheckInterval = heartbeatCheckInterval;
    }
}
