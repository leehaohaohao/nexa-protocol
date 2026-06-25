package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 消息分发器，根据 MessageType 路由到对应的 MessageHandler
 */
public class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    private final Map<MessageType, MessageHandler> handlers = new EnumMap<>(MessageType.class);

    public MessageDispatcher(List<MessageHandler> handlerList) {
        for (MessageHandler handler : handlerList) {
            handlers.put(handler.getType(), handler);
        }
    }

    /**
     * 根据消息类型分发到对应 Handler
     */
    public void dispatch(ChannelHandlerContext ctx, Envelope envelope) {
        MessageType type = envelope.getType();
        MessageHandler handler = handlers.get(type);

        if (handler != null) {
            try {
                handler.handle(ctx, envelope);
            } catch (Exception e) {
                log.error("handler error for message type {}", type, e);
            }
        } else {
            log.warn("no handler for message type: {}", type);
        }
    }
}
