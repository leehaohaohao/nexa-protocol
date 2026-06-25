package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.Disconnect.DisconnectRequest;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            return;
        }

        String runnerId = req.getRunnerId();
        sessionManager.remove(runnerId);

        try {
            listener.onDisconnect(runnerId, req.getReason());
        } catch (Exception e) {
            log.error("listener.onDisconnect error for {}", runnerId, e);
        }

        ctx.close();
    }
}
