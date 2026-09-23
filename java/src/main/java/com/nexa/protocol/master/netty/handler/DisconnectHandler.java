package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.Disconnect.DisconnectRequest;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 主动断开处理器：只能条件移除<b>发送方自身</b>的当前会话。
 *
 * <p>依据报文 runnerId 删除会话会让任意连接摘掉他人节点；因此这里先按发送 channel
 * 解析会话，再 {@code removeIfPresent} 条件移除，且仅当确实移除了当前绑定的会话时
 * 才通知 listener（与 {@code channelInactive}、心跳超时共用「仅条件移除成功才通知」规则，
 * 避免同一会话被通知两次）。
 */
public class DisconnectHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(DisconnectHandler.class);

    private final SessionManager sessionManager;
    private final NexaMasterListener listener;

    public DisconnectHandler(SessionManager sessionManager, NexaMasterListener listener) {
        this.sessionManager = sessionManager;
        this.listener = listener;
    }

    @Override
    public MessageType getType() {
        return MessageType.DISCONNECT_REQ;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Envelope envelope) {
        DisconnectRequest req;
        try {
            req = ProtocolCodec.parseDisconnectRequest(envelope.getPayload().toByteArray());
        } catch (Exception e) {
            log.error("failed to parse DisconnectRequest", e);
            ctx.close();
            return;
        }

        Optional<RunnerSession> resolved = SessionResolver.resolve(ctx, sessionManager);
        if (resolved.isEmpty()) {
            log.warn("disconnect from unregistered or superseded connection {}, not touching any session",
                    ctx.channel().remoteAddress());
            ctx.close();
            return;
        }

        RunnerSession session = resolved.get();
        if (!SessionResolver.matches(session, req.getRunnerId())) {
            log.warn("disconnect runner_id mismatch: declared={}, session={}, ignored",
                    req.getRunnerId(), session.getRunnerId());
            ctx.close();
            return;
        }

        // 仅条件移除自身当前会话；已被新连接接管时不触碰新会话
        if (sessionManager.removeIfPresent(session.getRunnerId(), session)) {
            try {
                listener.onDisconnect(session, req.getReason());
            } catch (Exception e) {
                log.error("listener.onDisconnect error for {}", session.getRunnerId(), e);
            }
        }

        ctx.close();
    }
}
