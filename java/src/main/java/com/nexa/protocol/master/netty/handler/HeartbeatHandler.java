package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 心跳处理器：会话取自<b>发送 channel 绑定</b>的会话，报文中的 runnerId 仅作一致性检查。
 *
 * <p>这样未注册连接、或已注册连接冒用他人 runnerId 的心跳都无法刷新任何节点的心跳时间，
 * 也不会污染负载，也不会把心跳响应发给被冒用的节点。
 */
public class HeartbeatHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    private final SessionManager sessionManager;
    private final NexaMasterListener listener;

    public HeartbeatHandler(SessionManager sessionManager, NexaMasterListener listener) {
        this.sessionManager = sessionManager;
        this.listener = listener;
    }

    @Override
    public MessageType getType() {
        return MessageType.HEARTBEAT_REQ;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Envelope envelope) {
        HeartbeatRequest req;
        try {
            req = ProtocolCodec.parseHeartbeatRequest(envelope.getPayload().toByteArray());
        } catch (Exception e) {
            log.error("failed to parse HeartbeatRequest", e);
            return;
        }

        Optional<RunnerSession> resolved = SessionResolver.resolve(ctx, sessionManager);
        if (resolved.isEmpty()) {
            log.warn("heartbeat from unregistered or superseded connection {}, ignored",
                    ctx.channel().remoteAddress());
            return;
        }

        RunnerSession session = resolved.get();
        if (!SessionResolver.matches(session, req.getRunnerId())) {
            log.warn("heartbeat runner_id mismatch: declared={}, session={}, ignored",
                    req.getRunnerId(), session.getRunnerId());
            return;
        }

        // 仅刷新发送方自身会话，并只向该会话回包
        session.updateHeartbeatTime();

        Envelope respEnv = ProtocolCodec.buildHeartbeatResponse(session.getRunnerId());
        session.send(respEnv);

        try {
            listener.onHeartbeat(session, req);
        } catch (Exception e) {
            log.warn("listener.onHeartbeat error for {}", session.getRunnerId(), e);
        }
    }
}
