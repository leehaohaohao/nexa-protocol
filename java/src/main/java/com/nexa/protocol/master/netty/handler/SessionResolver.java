package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.util.Optional;

/**
 * 会话身份解析入口：所有已注册消息都必须以「发送 channel 绑定的会话」为准。
 *
 * <p>绑定规则：{@link RegisterHandler} 认证通过后才把 runnerId 写入 channel 属性
 * （{@link #RUNNER_ID_ATTR}）。解析时要求注册表中该 runnerId 对应的会话 channel
 * 与本条连接的 channel <b>完全相同</b>，因此：
 * <ul>
 *   <li>未注册连接解析不到会话</li>
 *   <li>已被同 ID 新连接接管的旧连接解析不到会话</li>
 * </ul>
 *
 * <p>{@code Envelope.source_id} 与 payload 中的 runnerId 都是客户端提供的数据，
 * 只能用于一致性检查，不能用于选择会话。
 */
public final class SessionResolver {

    /** channel 上绑定的已认证 runnerId，仅注册成功后写入 */
    public static final AttributeKey<String> RUNNER_ID_ATTR = AttributeKey.valueOf("nexa.runnerId");

    private SessionResolver() {
    }

    /** 注册认证通过后绑定会话身份 */
    public static void bind(ChannelHandlerContext ctx, String runnerId) {
        bind(ctx.channel(), runnerId);
    }

    /** 注册认证通过后绑定会话身份（直接以 channel 为参数，便于测试与自定义 handler） */
    public static void bind(Channel channel, String runnerId) {
        channel.attr(RUNNER_ID_ATTR).set(runnerId);
    }

    /** 读取该连接已绑定的 runnerId（未注册返回 null） */
    public static String boundRunnerId(ChannelHandlerContext ctx) {
        return ctx.channel().attr(RUNNER_ID_ATTR).get();
    }

    /**
     * 解析发送方当前会话。
     *
     * <p>要求：连接已绑定身份、该 runnerId 仍在会话注册表中、且当前会话的 channel
     * 与本连接 channel 完全相同。任一条件不满足都返回空，调用方不得刷新状态、
     * 回业务响应、移除会话或触发业务回调。
     */
    public static Optional<RunnerSession> resolve(ChannelHandlerContext ctx, SessionManager sessionManager) {
        String runnerId = boundRunnerId(ctx);
        if (runnerId == null || runnerId.isEmpty()) {
            return Optional.empty();
        }
        return sessionManager.get(runnerId)
                .filter(session -> session.getChannel() == ctx.channel());
    }

    /**
     * 报文声明的 runnerId 是否与本会话身份一致（仅一致性检查，不用于选择会话）
     *
     * @param declaredRunnerId 报文中的 runnerId 或 source_id，允许为空表示未声明
     */
    public static boolean matches(RunnerSession session, String declaredRunnerId) {
        return declaredRunnerId == null
                || declaredRunnerId.isEmpty()
                || declaredRunnerId.equals(session.getRunnerId());
    }
}
