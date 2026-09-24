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
     * 连接断开清理。
     *
     * <p><b>事件所有权规则</b>：超时监控、主动断开、连接退出三条路径共用
     * 「谁成功移除当前会话，谁负责通知恰好一次」。因此这里不再凭 {@code isTimedOut()}
     * 推断「心跳监控器已经通知」——若监控器先标记超时、本路径抢先完成移除，
     * 双方都跳过通知会使掉线事件漏发。仅当条件移除失败（已被其他路径移除，
     * 或已被同 ID 新连接接管）时本路径才不通知。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Optional<RunnerSession> resolved = SessionResolver.resolve(ctx, sessionManager);
        if (resolved.isEmpty()) {
            return;
        }

        RunnerSession session = resolved.get();

        if (!sessionManager.removeIfPresent(session.getRunnerId(), session)) {
            // 已被超时监控 / 主动断开移除，或已被新连接接管：不由本路径通知
            return;
        }

        // 成功移除者通知一次；会话已被标记超时时按超时原因上报
        String reason = session.isTimedOut() ? "heartbeat_timeout" : "connection_lost";
        try {
            listener.onDisconnect(session, reason);
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
