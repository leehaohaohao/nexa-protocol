package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Task.TaskResponse;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskResultHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskResultHandler.class);

    private final SessionManager sessionManager;
    private final NexaMasterListener listener;

    public TaskResultHandler(SessionManager sessionManager, NexaMasterListener listener) {
        this.sessionManager = sessionManager;
        this.listener = listener;
    }

    @Override
    public MessageType getType() {
        return MessageType.TASK_DISPATCH_RESP;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Envelope envelope) {
        TaskResponse resp;
        try {
            resp = ProtocolCodec.parseTaskResponse(envelope.getPayload().toByteArray());
        } catch (Exception e) {
            log.error("failed to parse TaskResponse", e);
            return;
        }

        sessionManager.get(resp.getRunnerId()).ifPresent(session -> {
            try {
                listener.onTaskResult(session, resp);
            } catch (Exception e) {
                log.error("listener.onTaskResult error for {}", resp.getRunnerId(), e);
            }
        });
    }
}
