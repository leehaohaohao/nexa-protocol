package com.nexa.protocol.master.netty;

import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import com.nexa.protocol.master.netty.handler.MessageDispatcher;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class MasterChannelHandler extends SimpleChannelInboundHandler<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(MasterChannelHandler.class);
    private static final AttributeKey<String> RUNNER_ID_ATTR = AttributeKey.valueOf("nexa.runnerId");

    private final SessionManager sessionManager;
    private final NexaMasterListener listener;
    private final MessageDispatcher dispatcher;

    public MasterChannelHandler(SessionManager sessionManager, NexaMasterListener listener,
                                MessageDispatcher dispatcher) {
        this.sessionManager = sessionManager;
        this.listener = listener;
        this.dispatcher = dispatcher;
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

        dispatcher.dispatch(ctx, envelope);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String runnerId = ctx.channel().attr(RUNNER_ID_ATTR).get();
        if (runnerId == null) {
            return;
        }

        sessionManager.get(runnerId).ifPresent(session -> {
            if (session.getChannel() != ctx.channel()) {
                return;
            }

            sessionManager.removeIfPresent(runnerId, session);

            if (session.isTimedOut()) {
                return;
            }

            try {
                listener.onDisconnect(runnerId, "connection_lost");
            } catch (Exception e) {
                log.error("listener.onDisconnect error for {}", runnerId, e);
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("channel exception: {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
