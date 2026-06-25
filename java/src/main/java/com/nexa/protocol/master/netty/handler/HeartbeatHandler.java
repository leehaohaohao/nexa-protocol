package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        sessionManager.get(req.getRunnerId()).ifPresent(session -> {
            session.updateHeartbeatTime();

            Envelope respEnv = ProtocolCodec.buildHeartbeatResponse(req.getRunnerId());
            session.send(respEnv);

            try {
                listener.onHeartbeat(session, req);
            } catch (Exception e) {
                log.warn("listener.onHeartbeat error for {}", req.getRunnerId(), e);
            }
        });
    }
}
