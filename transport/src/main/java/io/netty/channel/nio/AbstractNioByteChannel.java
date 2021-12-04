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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    /**
     *
     *   是否允许半关闭，当客户端发送fin主动关闭连接时（客户端发送通道关闭，但接收通道还没关闭），服务端回复ack 随后进入close_wait状态（服务端接收通道关闭，发送通道没有关闭）
     *   但是服务端允许在close_wait状态不调用close方法（从而无法发送fin到客户端）
     *   此时客户端的状态为fin_wait_2，服务端的状态为close_wait  为半关闭状态
     * */
    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            if (!isInputShutdown0()) {
                if (isAllowHalfClosure(config())) {
                    shutdownInput();
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            } else {
                inputClosedSeenErrorOnRead = true;
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);

            // If oom will close the read event, release connection.
            // See https://github.com/netty/netty/issues/10434
            if (close || cause instanceof OutOfMemoryError || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        public final void read() {
            final ChannelConfig config = config();
            //如果inputClosedSeenErrorOnRead = true ，移除对SelectionKey.OP_READ事件。
            //inputClosedSeenErrorOnRead表示当前channel读取通道已经关闭（close_wait状态），closeOnRead方法中设置
            if (shouldBreakReadReady(config)) {
                clearReadPending();
                return;
            }
            final ChannelPipeline pipeline = pipeline();
            //PooledByteBufAllocator ByteBuf具体的分配器
            final ByteBufAllocator allocator = config.getAllocator();
            //自适应ByteBuf分配器 AdaptiveRecvByteBufAllocator 需要与具体的ByteBuf分配器配合使用 比如这里的PooledByteBufAllocator
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            //allocHandler用于统计每次读取数据的大小，方便下次分配合适大小的ByteBuf
            allocHandle.reset(config);

            /**
             * AdaptiveRecvByteBufAllocator 只负责调整分配给recvbuf的容量大小，里边保存的属性全部是统计每次recvbuf读取数据的容量变化
             *
             * PooledByteBufAllocator 是具体负责分配recvbuf内存的，容量由AdaptiveRecvByteBufAllocator决定
             * */

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                do {
                    //利用PooledByteBufAllocator分配合适大小的byteBuf 初始大小为2048
                    //每一轮开始读取之前 都需要为每一轮分配独立的堆外内存 不能共用
                    byteBuf = allocHandle.allocate(allocator);
                    //记录本次读取了多少字节数
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    //如果本次没有读取到任何字节，则退出循环 进行下一轮事件轮询
                    // -1 表示客户端主动关闭了连接
                    if (allocHandle.lastBytesRead() <= 0) {
                        // nothing was read. release the buffer.
                        byteBuf.release();
                        byteBuf = null;
                        //当客户端主动关闭连接时（客户端发送fin1），会触发read就绪事件，这里从channel读取的数据会是-1
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        break;
                    }

                    //增加读取的message数量 这里表示读取的次数
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    //客户端NioSocketChannel的pipeline中触发ChannelRead事件
                    pipeline.fireChannelRead(byteBuf);
                    //下一轮读取开始重新分配内存 缓存下一轮的数据，本次读取的堆外内存继续在pipeline中传递处理
                    // channelHandler有可能是线程池处理 fireChannelRead立马返回，所以byteBuf每轮都应该是独立的不能共用
                    byteBuf = null;
                } while (allocHandle.continueReading());

                //统计本次recvbuf读取容量情况，决定下次是否扩容或者缩容
                allocHandle.readComplete();
                //在NioSocketChannel的pipeline中触发ChannelReadComplete事件，表示一次read事件处理完毕
                //但这并不表示 客户端发送来的数据已经全部读完，因为如果数据太多的话，这里只会读取16次，剩下的会等到下次read事件到来后在处理
                pipeline.fireChannelReadComplete();

                if (close) {
                    //此时客户端发送fin1（fi_wait_1状态）主动关闭连接，服务端接收到fin，并回复ack进入close_wait状态
                    //在服务端进入close_wait状态 需要调用close 方法向客户端发送fin_ack，服务端才能结束close_wait状态
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254

                /**
                 * autoread提供一种背压机制。防止oom
                 *
                 * If you have not set autoread to false you may get into trouble
                 * if one channel writes a lot of data before the other can consume it.
                 * As it's all asynchronous you may end up with buffers that have too much data and hit OOME.
                 *
                 * */
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        return doWriteInternal(in, in.current());
    }

    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            //用于处理io.netty.channel.nio.AbstractNioByteChannel.doWrite中的发送逻辑
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }

            final int localFlushedAmount = doWriteBytes(buf);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            //文件已经传输完毕
            if (region.transferred() >= region.count()) {
                in.remove();
                //当前FileRegion中的文件数据已经传输完毕，本次writeLoop并没有写任何数据，所以返回0 WriteSpinCount不变
                return 0;
            }

            //零拷贝的方式传输文件
            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        //走到这里表示 此时Socket已经写不进去了 退出writeLoop，注册OP_WRITE事件
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = config().getWriteSpinCount();
        do {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }

            return newDirectBuffer(buf);
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    /**
     * 注意 这里是没写完或者写操作被限制了
     * 1：如果是Socket缓冲区满导致的不能写，这时需要注册OP_WRITE事件，等待Socket缓冲区可写时，在flush写入
     * 2：如果是没写完或者写操作被限制，这时Socket是可写的，只不过SubReactor要处理其他Channel,所以不能注册OP_WRITE事件，否则会一直被通知
     * 这里就需要将flush操作封装成异步任务，等到SubReactor处理完其他Channel上的IO事件，在回过头来执行flush写入
     *
     * Netty是全异步操作，没写完的内容会继续保存在缓冲区ChannelOutBoundBuffer中，flush就是将ChannelOutBoundBuffer中的等待写入数据，写入到
     * Socket中
     * */
    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        if (setOpWrite) {
            //这里处理还没写满16次 但是socket缓冲区已满写不进去的情况 注册write事件
            // 什么时候socket可写了， epoll会通知reactor线程继续写
            setOpWrite();
        } else {
            //这里处理的是socket缓冲区依然可写，但是写了16次还没写完，这时就不能在写了，reactor线程需要处理其他channel上的io事件
            //把当前写任务封装task提交给reactor，等reactor执行完其他channel上的io操作，在执行异步任务写操作
            // 目的就要是保证reactor可以均衡的处理channel上的io操作。
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.
            //必须清除OP_WRITE事件，此时Socket对应的缓冲区依然是可写的，只不过当前channel写够了16次，被SubReactor限制了。
            // 这样SubReactor可以腾出手来处理其他channel上的IO事件。这里如果不清除OP_WRITE事件，则会一直被通知。
            clearOpWrite();

            // Schedule flush again later so other tasks can be picked up in the meantime
            //如果本次writeLoop还没写完，则提交flushTask到SubReactor
            //释放SubReactor让其可以继续处理其他Channel上的IO事件
            eventLoop().execute(flushTask);

            /**
             * 因为这里的情况时socket依然可写，但是已经写满16次了，直接创建flushTask，等reactor执行完其他channel上的IO事件
             * 最后再来执行flushTask。
             *
             * 这里不注册OP_WRITE事件的原因在于，此时Socket是可写的，如果注册了OP_WRITE事件会一直被通知。
             * */
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
