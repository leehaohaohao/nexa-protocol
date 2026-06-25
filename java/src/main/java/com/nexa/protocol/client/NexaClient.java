package com.nexa.protocol.client;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.codec.FrameCodec;
import com.nexa.protocol.codec.ProtocolCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP 客户端，用于与主节点通信
 */
public class NexaClient {

    private Socket socket;
    private InputStream input;
    private OutputStream output;

    // ---- 身份信息 ----
    private final String runnerId;
    private final String hostname;
    private final String ip;
    private final String version;

    // ---- 心跳配置 ----
    private final Duration heartbeatInterval;

    // ---- 生命周期控制 ----
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatFuture;

    private NexaClient(Builder builder) {
        this.runnerId = builder.runnerId;
        this.hostname = builder.hostname != null ? builder.hostname : getLocalHostname();
        this.ip = builder.ip;
        this.version = builder.version;
        this.heartbeatInterval = builder.heartbeatInterval;
    }

    /**
     * 建立 TCP 连接，5 秒超时
     */
    public void connect(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), 5000);
        input = socket.getInputStream();
        output = socket.getOutputStream();
    }

    /**
     * 发送注册请求并等待响应
     */
    public RegisterResponse register() throws IOException {
        Envelope env = ProtocolCodec.buildRegisterRequest(runnerId, hostname, ip, version);
        sendEnvelope(env);

        Envelope respEnv = readEnvelope();
        if (respEnv.getType() != MessageType.REGISTER_RESP) {
            throw new IOException("unexpected response type: " + respEnv.getType());
        }
        return RegisterResponse.parseFrom(respEnv.getPayload());
    }

    /**
     * 启动定时心跳，守护线程执行
     */
    public void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexa-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (stopped.get()) return;
            try {
                Envelope env = ProtocolCodec.buildHeartbeatRequest(runnerId, 0, 0, 0);
                sendEnvelope(env);
            } catch (IOException e) {
                // 发送失败则停止心跳
                stopped.set(true);
            }
        }, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 发送断开消息（best-effort）并关闭连接
     */
    public void disconnect(String reason) {
        // 保证只执行一次关闭流程
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        // 停止心跳
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }
        // best-effort 发送断开请求
        try {
            Envelope env = ProtocolCodec.buildDisconnectRequest(runnerId, reason);
            sendEnvelope(env);
        } catch (IOException ignored) {
        }
        closeQuietly();
    }

    /**
     * 从连接中读取一个 Envelope，供业务层读取服务器推送的消息
     */
    public Envelope readEnvelope() throws IOException {
        byte[] data = FrameCodec.readFrame(input);
        return ProtocolCodec.parseEnvelope(data);
    }

    /** 获取底层 Socket，供业务层使用 */
    public Socket getSocket() {
        return socket;
    }

    /** 获取输入流 */
    public InputStream getInput() {
        return input;
    }

    /** 获取输出流 */
    public OutputStream getOutput() {
        return output;
    }

    /** 序列化并发送一帧 */
    private void sendEnvelope(Envelope env) throws IOException {
        byte[] data = env.toByteArray();
        FrameCodec.writeFrame(output, data);
    }

    /** 静默关闭连接 */
    private void closeQuietly() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    /** 获取本机主机名，失败返回 "unknown" */
    private static String getLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String runnerId;
        private String hostname;
        private String ip;
        private String version;
        private Duration heartbeatInterval = Duration.ofSeconds(10);

        public Builder runnerId(String runnerId) {
            this.runnerId = runnerId;
            return this;
        }

        public Builder hostname(String hostname) {
            this.hostname = hostname;
            return this;
        }

        public Builder ip(String ip) {
            this.ip = ip;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder heartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
            return this;
        }

        public NexaClient build() {
            return new NexaClient(this);
        }
    }
}
