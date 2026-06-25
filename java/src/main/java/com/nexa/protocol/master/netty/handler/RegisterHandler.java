package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(RegisterHandler.class);
    private static final AttributeKey<String> RUNNER_ID_ATTR = AttributeKey.valueOf("nexa.runnerId");

    private final SessionManager sessionManager;
    private final NexaMasterListener listener;

    public RegisterHandler(SessionManager sessionManager, NexaMasterListener listener) {
        this.sessionManager = sessionManager;
        this.listener = listener;
    }

    @Override
    public MessageType getType() {
        return MessageType.REGISTER_REQ;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Envelope envelope) {
        RegisterRequest req;
        try {
            req = ProtocolCodec.parseRegisterRequest(envelope.getPayload().toByteArray());
        } catch (Exception e) {
            log.error("failed to parse RegisterRequest", e);
            return;
        }

        String runnerId = req.getRunnerId();
        RunnerSession session = new RunnerSession(
                runnerId, ctx.channel(), req.getHostname(), req.getIp(), req.getVersion());

        RunnerSession oldSession = sessionManager.register(session);
        if (oldSession != null) {
            log.info("runner {} reconnected, closing old session", runnerId);
            oldSession.close();
        }

        ctx.channel().attr(RUNNER_ID_ATTR).set(runnerId);

        RegisterResponse resp;
        try {
            resp = listener.onRegister(session, req);
        } catch (Exception e) {
            log.error("listener.onRegister error for {}", runnerId, e);
            resp = RegisterResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("internal error: " + e.getMessage())
                    .build();
        }

        Envelope respEnv = ProtocolCodec.buildRegisterResponse(runnerId, resp.getSuccess(), resp.getMessage());
        ctx.writeAndFlush(respEnv.toByteArray());
    }
}
