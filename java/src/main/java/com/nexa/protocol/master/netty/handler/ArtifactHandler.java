package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Artifact.ArtifactRequest;
import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArtifactHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ArtifactHandler.class);
    private static final AttributeKey<String> RUNNER_ID_ATTR = AttributeKey.valueOf("nexa.runnerId");

    private final SessionManager sessionManager;
    private final NexaMasterListener listener;

    public ArtifactHandler(SessionManager sessionManager, NexaMasterListener listener) {
        this.sessionManager = sessionManager;
        this.listener = listener;
    }

    @Override
    public MessageType getType() {
        return MessageType.ARTIFACT_REQ;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Envelope envelope) {
        ArtifactRequest req;
        try {
            req = ProtocolCodec.parseArtifactRequest(envelope.getPayload().toByteArray());
        } catch (Exception e) {
            log.error("failed to parse ArtifactRequest", e);
            return;
        }

        String runnerId = ctx.channel().attr(RUNNER_ID_ATTR).get();
        if (runnerId == null) {
            log.warn("artifact request from unregistered channel {}", ctx.channel().remoteAddress());
            return;
        }

        sessionManager.get(runnerId).ifPresent(session -> {
            if (session.getChannel() != ctx.channel()) {
                log.warn("artifact request channel mismatch for runner {}", runnerId);
                return;
            }
            try {
                listener.onArtifactRequest(session, envelope, req);
            } catch (Exception e) {
                log.error("listener.onArtifactRequest error for {}", runnerId, e);
            }
        });
    }
}
