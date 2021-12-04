/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.example.echo;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Handler implementation for the echo server.
 */
@Sharable
public class EchoServerHandler extends ChannelInboundHandlerAdapter {


    /**
     * ctx.write(msg) ： write事件从当前ChannelHandler在pipeline中向前传递
     *
     * ctx.channel().write(msg) ： write事件从pipeline的末尾tailContext开始向前传递
     *
     * */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        //此处的msg就是Netty在read loop中从NioSocketChannel中读取到ByteBuffer
        ChannelFuture future = ctx.write(msg);
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                Throwable cause = future.cause();
                if (cause != null) {

                    //process happed exception will be here and you can call ctx.write(msg) to keep spreading forward
                    // the write event in the pipeline
                    ctx.write(msg);
                } else {
                    // when msg has been write to socket successfully, netty will notify here!!

                }
            }
        });
        ctx.writeAndFlush(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        //本次OP_READ事件处理完毕
        ctx.flush();
        ctx.channel().flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised.
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {

        if (ctx.channel().isWritable()) {

        } else {

        }
    }
}
