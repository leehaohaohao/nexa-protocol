package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Artifact.ArtifactRequest;
import com.nexa.protocol.Common.MessageType;
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
 * 产物请求处理器：按发送 channel 绑定的会话鉴权（与其余已注册消息共用
 * {@link SessionResolver} 的统一规则），并用 {@code Envelope.source_id} 做一致性检查。
 *
 * <p>{@code ArtifactRequest} 本身不带 runnerId，因此以 source_id 作为声明身份；
 * 它是客户端数据，只用于一致性检查，会话选择始终依据 channel 绑定。
 */
public class ArtifactHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ArtifactHandler.class);

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

        Optional<RunnerSession> resolved = SessionResolver.resolve(ctx, sessionManager);
        if (resolved.isEmpty()) {
            log.warn("artifact request from unregistered or superseded connection {}, ignored",
                    ctx.channel().remoteAddress());
            return;
        }

        RunnerSession session = resolved.get();
        if (!SessionResolver.matches(session, envelope.getSourceId())) {
            log.warn("artifact request source_id mismatch: declared={}, session={}, ignored",
                    envelope.getSourceId(), session.getRunnerId());
            return;
        }

        try {
            listener.onArtifactRequest(session, envelope, req);
        } catch (Exception e) {
            log.error("listener.onArtifactRequest error for {}", session.getRunnerId(), e);
        }
    }
}
