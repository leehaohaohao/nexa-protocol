package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Query.ContainerStatusResponse;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 容器状态回执处理器：会话取自发送 channel 绑定的会话，并核对回执声明的 runnerId，
 * 避免伪造回执污染其他节点的状态查询结果。
 */
public class ContainerStatusHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ContainerStatusHandler.class);

    private final SessionManager sessionManager;
    private final NexaMasterListener listener;

    public ContainerStatusHandler(SessionManager sessionManager, NexaMasterListener listener) {
        this.sessionManager = sessionManager;
        this.listener = listener;
    }

    @Override
    public MessageType getType() {
        return MessageType.CONTAINER_STATUS_RESP;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Envelope envelope) {
        ContainerStatusResponse resp;
        try {
            resp = ProtocolCodec.parseContainerStatusResponse(envelope.getPayload().toByteArray());
        } catch (Exception e) {
            log.error("failed to parse ContainerStatusResponse", e);
            return;
        }

        Optional<RunnerSession> resolved = SessionResolver.resolve(ctx, sessionManager);
        if (resolved.isEmpty()) {
            log.warn("container status from unregistered or superseded connection {}, ignored",
                    ctx.channel().remoteAddress());
            return;
        }

        RunnerSession session = resolved.get();
        if (!SessionResolver.matches(session, resp.getRunnerId())) {
            log.warn("container status runner_id mismatch: declared={}, session={}, ignored",
                    resp.getRunnerId(), session.getRunnerId());
            return;
        }

        try {
            listener.onContainerStatus(session, resp);
        } catch (Exception e) {
            log.error("listener.onContainerStatus error for {}", session.getRunnerId(), e);
        }
    }
}
