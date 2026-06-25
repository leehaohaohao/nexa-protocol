package com.nexa.protocol.master.netty;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.Disconnect.DisconnectRequest;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Heartbeat.HeartbeatResponse;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MasterChannelHandler extends SimpleChannelInboundHandler<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(MasterChannelHandler.class);
    private static final AttributeKey<String> RUNNER_ID_ATTR = AttributeKey.valueOf("nexa.runnerId");

    private final SessionManager sessionManager;
    private final NexaMasterListener listener;

    public MasterChannelHandler(SessionManager sessionManager, NexaMasterListener listener) {
        this.sessionManager = sessionManager;
        this.listener = listener;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] msg) {
        Envelope envelope;
        try {
            envelope = ProtocolCodec.parseEnvelope(msg);
        } catch (Exception e) {
            log.error("failed to parse envelope", e);
            return;
        }

        MessageType type = envelope.getType();
        switch (type) {
            case REGISTER_REQ -> handleRegister(ctx, envelope);
            case HEARTBEAT_REQ -> handleHeartbeat(ctx, envelope);
            case DISCONNECT_REQ -> handleDisconnect(ctx, envelope);
            default -> log.warn("unknown message type: {}", type);
        }
    }

    private void handleRegister(ChannelHandlerContext ctx, Envelope envelope) {
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

    private void handleHeartbeat(ChannelHandlerContext ctx, Envelope envelope) {
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

    private void handleDisconnect(ChannelHandlerContext ctx, Envelope envelope) {
        DisconnectRequest req;
        try {
            req = ProtocolCodec.parseDisconnectRequest(envelope.getPayload().toByteArray());
        } catch (Exception e) {
            log.error("failed to parse DisconnectRequest", e);
            return;
        }

        String runnerId = req.getRunnerId();
        sessionManager.remove(runnerId);
        ctx.channel().attr(RUNNER_ID_ATTR).set(null);

        try {
            listener.onDisconnect(runnerId, req.getReason());
        } catch (Exception e) {
            log.error("listener.onDisconnect error for {}", runnerId, e);
        }

        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String runnerId = ctx.channel().attr(RUNNER_ID_ATTR).get();
        if (runnerId == null) {
            return;
        }

        // only remove if the session's channel is still this channel (avoid race with reconnect)
        sessionManager.get(runnerId).ifPresent(session -> {
            if (session.getChannel() == ctx.channel()) {
                sessionManager.remove(runnerId);
                try {
                    listener.onDisconnect(runnerId, "connection_lost");
                } catch (Exception e) {
                    log.error("listener.onDisconnect error for {}", runnerId, e);
                }
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("channel exception: {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
