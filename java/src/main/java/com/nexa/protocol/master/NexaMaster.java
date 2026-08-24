package com.nexa.protocol.master;

import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.master.netty.MasterChannelHandler;
import com.nexa.protocol.master.netty.NexaFrameDecoder;
import com.nexa.protocol.master.netty.NexaFrameEncoder;
import com.nexa.protocol.master.netty.handler.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class NexaMaster {

    private static final Logger log = LoggerFactory.getLogger(NexaMaster.class);

    private final String host;
    private final int port;
    private final int maxFrameSize;
    private final Duration heartbeatTimeout;
    private final Duration heartbeatCheckInterval;
    private final NexaMasterListener listener;

    private final List<MessageHandler> handlers;
    private final SessionManager sessionManager;
    private HeartbeatMonitor heartbeatMonitor;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private NexaMaster(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.maxFrameSize = builder.maxFrameSize;
        this.heartbeatTimeout = builder.heartbeatTimeout;
        this.heartbeatCheckInterval = builder.heartbeatCheckInterval;
        this.listener = builder.listener;
        this.sessionManager = new SessionManager();

        this.handlers = new ArrayList<>();
        this.handlers.add(new RegisterHandler(sessionManager, listener));
        this.handlers.add(new HeartbeatHandler(sessionManager, listener));
        this.handlers.add(new DisconnectHandler(sessionManager, listener));
        this.handlers.add(new TaskResultHandler(sessionManager, listener));
        this.handlers.add(new ContainerStatusHandler(sessionManager, listener));
        this.handlers.add(new ContainerLogsHandler(sessionManager, listener));
        this.handlers.addAll(builder.handlers);
    }

    /**
     * 注册自定义消息处理器，扩展协议消息类型
     */
    public void registerHandler(MessageHandler handler) {
        handlers.add(handler);
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        // 构建消息处理器链（内置 handler + 自定义注册）
        MessageDispatcher dispatcher = new MessageDispatcher(handlers);
        MasterChannelHandler handler = new MasterChannelHandler(sessionManager, listener, dispatcher);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new NexaFrameDecoder(maxFrameSize));
                        ch.pipeline().addLast(new NexaFrameEncoder());
                        ch.pipeline().addLast(handler);
                    }
                });

        serverChannel = bootstrap.bind(host, port).sync().channel();
        log.info("NexaMaster started on {}:{}", host, port);

        heartbeatMonitor = new HeartbeatMonitor(sessionManager, listener, heartbeatTimeout, heartbeatCheckInterval);
        heartbeatMonitor.start();
    }

    public void shutdown() {
        log.info("NexaMaster shutting down...");

        if (heartbeatMonitor != null) {
            heartbeatMonitor.stop();
        }

        for (RunnerSession session : sessionManager.allSessions()) {
            session.close();
        }

        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }

        log.info("NexaMaster stopped");
    }

    public boolean sendTo(String runnerId, Envelope envelope) {
        return sessionManager.get(runnerId)
                .map(session -> session.send(envelope))
                .orElse(false);
    }

    public void broadcast(Envelope envelope) {
        for (RunnerSession session : sessionManager.allSessions()) {
            session.send(envelope);
        }
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public int getOnlineCount() {
        return sessionManager.size();
    }

    public static Builder builder(NexaMasterListener listener) {
        return new Builder(listener);
    }

    public static class Builder {
        private final NexaMasterListener listener;
        private final List<MessageHandler> handlers = new ArrayList<>();
        private String host = "0.0.0.0";
        private int port = 9090;
        private int maxFrameSize = 10 * 1024 * 1024;
        private Duration heartbeatTimeout = Duration.ofSeconds(30);
        private Duration heartbeatCheckInterval = Duration.ofSeconds(5);

        Builder(NexaMasterListener listener) {
            this.listener = listener;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder maxFrameSize(int maxFrameSize) {
            this.maxFrameSize = maxFrameSize;
            return this;
        }

        public Builder heartbeatTimeout(Duration timeout) {
            this.heartbeatTimeout = timeout;
            return this;
        }

        public Builder heartbeatCheckInterval(Duration interval) {
            this.heartbeatCheckInterval = interval;
            return this;
        }

        public Builder addHandler(MessageHandler handler) {
            this.handlers.add(handler);
            return this;
        }

        public NexaMaster build() {
            return new NexaMaster(this);
        }
    }
}
