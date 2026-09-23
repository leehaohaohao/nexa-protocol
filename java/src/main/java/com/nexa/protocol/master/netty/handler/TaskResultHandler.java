package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Task.TaskResponse;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 任务回执处理器：会话取自发送 channel 绑定的会话，并核对回执声明的 runnerId。
 *
 * <p>避免任意连接伪造他人 runnerId 的任务回执，污染其他节点的任务结果。
 */
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

        Optional<RunnerSession> resolved = SessionResolver.resolve(ctx, sessionManager);
        if (resolved.isEmpty()) {
            log.warn("task result from unregistered or superseded connection {}, ignored",
                    ctx.channel().remoteAddress());
            return;
        }

        RunnerSession session = resolved.get();
        if (!SessionResolver.matches(session, resp.getRunnerId())) {
            log.warn("task result runner_id mismatch: declared={}, session={}, ignored",
                    resp.getRunnerId(), session.getRunnerId());
            return;
        }

        try {
            listener.onTaskResult(session, resp);
        } catch (Exception e) {
            log.error("listener.onTaskResult error for {}", session.getRunnerId(), e);
        }
    }
}
