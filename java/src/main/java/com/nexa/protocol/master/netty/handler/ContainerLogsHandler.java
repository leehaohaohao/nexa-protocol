package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Query.ContainerLogsResponse;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContainerLogsHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ContainerLogsHandler.class);

    private final SessionManager sessionManager;
    private final NexaMasterListener listener;

    public ContainerLogsHandler(SessionManager sessionManager, NexaMasterListener listener) {
        this.sessionManager = sessionManager;
        this.listener = listener;
    }

    @Override
    public MessageType getType() {
        return MessageType.CONTAINER_LOGS_RESP;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Envelope envelope) {
        ContainerLogsResponse resp;
        try {
            resp = ProtocolCodec.parseContainerLogsResponse(envelope.getPayload().toByteArray());
        } catch (Exception e) {
            log.error("failed to parse ContainerLogsResponse", e);
            return;
        }

        sessionManager.get(resp.getRunnerId()).ifPresent(session -> {
            try {
                listener.onContainerLogs(session, resp);
            } catch (Exception e) {
                log.error("listener.onContainerLogs error for {}", resp.getRunnerId(), e);
            }
        });
    }
}
