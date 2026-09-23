package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 注册处理器：先认证、后接管。
 *
 * <p>顺序约束：只有认证通过的新连接才能原子替换同 runnerId 的旧会话。
 * 认证失败时不影响已在线的合法会话，并且不得为该连接绑定会话身份，
 * 避免其断开时误报节点离线。
 */
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
            rejectAndClose(ctx, "", "invalid register request", e.getMessage());
            return;
        }

        String runnerId = req.getRunnerId();
        if (runnerId == null || runnerId.isEmpty()) {
            log.warn("register request without runner_id from {}", ctx.channel().remoteAddress());
            rejectAndClose(ctx, "", "runner_id is required", null);
            return;
        }

        // 候选会话：此时尚未注册，认证失败不得影响任何现有会话
        RunnerSession candidate = new RunnerSession(
                runnerId, ctx.channel(), req.getHostname(), req.getIp(), req.getVersion());

        // 1. 先认证（不触碰会话注册表）
        RegisterResponse resp;
        try {
            resp = listener.onRegister(candidate, req);
        } catch (Exception e) {
            log.error("listener.onRegister error for {}", runnerId, e);
            rejectAndClose(ctx, runnerId, "internal error", e.getMessage());
            return;
        }

        if (resp == null || !resp.getSuccess()) {
            String reason = resp == null ? "register rejected" : resp.getMessage();
            log.warn("register rejected for {}: {}", runnerId, reason);
            // 拒绝：先可靠发出失败响应，再关闭未认证连接；不注册、不绑定身份、不影响旧会话
            rejectAndClose(ctx, runnerId, reason, null);
            return;
        }

        // 2. 认证通过后才接管：原子替换同 runnerId 的旧会话
        RunnerSession oldSession = sessionManager.register(candidate);
        if (oldSession != null && oldSession.getChannel() != ctx.channel()) {
            log.info("runner {} reconnected, closing old session", runnerId);
            // 旧连接断开时 channelInactive 会发现当前会话已不是它，从而不会误删新会话
            oldSession.close();
        }

        // 3. 绑定会话身份（此后该连接的断开事件才代表该 runner 离线）
        ctx.channel().attr(RUNNER_ID_ATTR).set(runnerId);

        Envelope respEnv = ProtocolCodec.buildRegisterResponse(runnerId, true, resp.getMessage());
        ctx.writeAndFlush(respEnv.toByteArray());
        log.info("runner {} registered", runnerId);
    }

    /**
     * 拒绝注册：先可靠写出失败响应，再关闭连接。
     */
    private void rejectAndClose(ChannelHandlerContext ctx, String runnerId, String message, String detail) {
        RegisterResponse resp = RegisterResponse.newBuilder()
                .setSuccess(false)
                .setMessage(detail == null ? message : message + ": " + detail)
                .build();
        Envelope respEnv = ProtocolCodec.buildRegisterResponse(runnerId, resp.getSuccess(), resp.getMessage());

        // 先 flush 完成再关闭，保证拒绝原因能送达对端
        ctx.writeAndFlush(respEnv.toByteArray()).addListener(ChannelFutureListener.CLOSE);
    }
}
