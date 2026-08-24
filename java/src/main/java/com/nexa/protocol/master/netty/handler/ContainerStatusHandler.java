package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Query.ContainerStatusResponse;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        sessionManager.get(resp.getRunnerId()).ifPresent(session -> {
            try {
                listener.onContainerStatus(session, resp);
            } catch (Exception e) {
                log.error("listener.onContainerStatus error for {}", resp.getRunnerId(), e);
            }
        });
    }
}
