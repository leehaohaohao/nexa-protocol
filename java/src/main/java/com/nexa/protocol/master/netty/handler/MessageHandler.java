package com.nexa.protocol.master.netty.handler;

import com.nexa.protocol.Common.MessageType;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import io.netty.channel.ChannelHandlerContext;

/**
 * 消息处理器接口，每种 MessageType 实现一个独立 Handler
 */
public interface MessageHandler {

    /**
     * 该 Handler 处理的消息类型
     */
    MessageType getType();

    /**
     * 处理消息
     */
    void handle(ChannelHandlerContext ctx, Envelope envelope);
}
