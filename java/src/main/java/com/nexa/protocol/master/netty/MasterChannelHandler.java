package com.nexa.protocol.master.netty;

import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import com.nexa.protocol.master.netty.handler.MessageDispatcher;
import com.nexa.protocol.master.netty.handler.SessionResolver;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

@ChannelHandler.Sharable
public class MasterChannelHandler extends SimpleChannelInboundHandler<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(MasterChannelHandler.class);

    private final SessionManager sessionManager;
    private final NexaMasterListener listener;
    private final MessageDispatcher dispatcher;

    public MasterChannelHandler(SessionManager sessionManager, NexaMasterListener listener,
                                MessageDispatcher dispatcher) {
        this.sessionManager = sessionManager;
        this.listener = listener;
        this.dispatcher = dispatcher;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] msg) {
        Envelope envelope;
        try {
            envelope = ProtocolCodec.parseEnvelope(msg);
        } catch (Exception e) {
            log.error("failed to parse envelope", e);
            return;
        }

        dispatcher.dispatch(ctx, envelope);
    }

    /**
     * 连接断开清理：与心跳超时、主动断开共用「仅条件移除成功才通知」规则。
     *
     * <p>已被同 ID 新连接接管的旧连接解析不到会话，因此不会误删新会话或误报离线；
     * 心跳超时路径已由 {@code HeartbeatMonitor} 条件移除并发过一次通知，此处不再重复。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Optional<RunnerSession> resolved = SessionResolver.resolve(ctx, sessionManager);
        if (resolved.isEmpty()) {
            return;
        }

        RunnerSession session = resolved.get();

        if (!sessionManager.removeIfPresent(session.getRunnerId(), session)) {
            return;
        }

        if (session.isTimedOut()) {
            // 超时事件已由 HeartbeatMonitor 发出
            return;
        }

        try {
            listener.onDisconnect(session, "connection_lost");
        } catch (Exception e) {
            log.error("listener.onDisconnect error for {}", session.getRunnerId(), e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("channel exception: {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
