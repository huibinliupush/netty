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
    //表示Input已经shutdown了，再次对channel进行读取返回-1  设置该标志
    //表示此时Channel的读通道已经关闭了，不能再继续响应`OP_READ事件`，
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
     *   此时客户端的状态为fin_wait_2，服务端的状态为close_wait  为半关闭状态  调用的shutdown
     *
     *   如果调用的close方法则 无法实现半关闭
     *
     *   https://issues.redhat.com/browse/NETTY-236
     * */
    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {
        /**
         * 此时客户端的状态为fin_wait2 服务端的状态为close_wait
         * 如果客户端是调用的close方法发送的fin,则进入全关闭状态，在fin_wait2不能接受服务端的数据，直接返回rst给服务端，但服务端在close_wait状态还可以向客户端发送数据，只是客户端不在接受
         * 如果客户端调用的是shutdown方法发送fin,则进入半关闭状态，只是将发送方向的通道关闭，但还没有关闭接受通道还是可以接受服务端的数据在fin_wait2，但是这个状态是有超时时间限制的由操作系统控制
         *
         * 这里的逻辑是服务端在收到客户端的fin并回复ack给客户端后进入close_wait状态，需要调用close方法给客户端发送fin 结束close_wait状态进行last_ack状态
         *
         * 如果进程异常退出了，内核就会发送 RST 报文来关闭，它可以不走四次挥手流程，是一个暴力关闭连接的方式。
         *
         * 安全关闭连接的方式必须通过四次挥手，它由进程调用 close 和 shutdown 函数发起 FIN 报文（
         * shutdown 参数须传入 SHUTWR（关闭写） 或者 SHUTRDWR（同时关闭读写） 才会发送 FIN）。关闭读不会发送fin
         *
         * 这里无论服务端还是客户端在收到fin的时候 在支持半关闭的情况下，首先都需要shutdwon input
         *
         * 1:客户端shutdownOutput 发送fin 到服务端。服务端回复ack后，会触发read事件走到这里，首先需要shutdownInput。随后调用close或者shutdownOutput结束close_wait状态
         * 2:服务端调用close或者shutdownOutput结束close_wait状态后，客户端会收到服务端的fin，客户端触发read事件走到这里，shutdownInput
         * */
        private void closeOnRead(ChannelPipeline pipeline) {
            //判断接收方向是否关闭，这里肯定是没有关闭的
            if (!isInputShutdown0()) {
                //如果接收方向还没有关闭 继续判断是否支持半关闭（客户端可以继续接收数据但不能发送数据，服务端可以继续发送数据但不能接受数据）
                if (isAllowHalfClosure(config())) {
                    // 如果支持半关闭，服务端这里需要首先关闭接收方向的通道，语义是不在接受新的数据，但是可以继续发送数据
                    // 注意调用shutdownInput后channel不会关闭，只是说服务端在close_wait状态还可以继续发送数据，但不能接收数据，但状态还是close_wait状态
                    // 直到服务端主动调用shutdownOutput，向客户端发送fin 才能结束close_wait状态进入last-ack
                    // shutdownInput不会发送fin   shutdownOutput才会发送fin
                    shutdownInput();
                    //pipeline中触发userEvent事件 -> ChannelInputShutdownEvent
                    //通知用户此时，channel的接收方法通道已经关闭 不在接受新的数据,注意此时channel并没有关闭
                    //可以在ChannelInputShutdownEvent事件回调中 继续向客户端发送数据，然后调用close向客户端发送fin
                    //结束服务端的close_wait状态进入last_ack，客户端结束fin_wait2状态进入time_wait
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    //如果不支持半关闭，则服务端直接调用close方法向客户端发送fin,结束close_wait状态进如last_ack状态
                    //半关闭状态下，客户端在 FIN_WAIT2 状态下接收到 服务端 close_wait 状态下发送的 fin 之后还是会来到这里
                    //关闭连接释放资源
                    close(voidPromise());
                }
            } else {
                /**
                 * https://github.com/netty/netty/commit/ed0668384b393c3502c2136e3cc412a5c8c9056e
                 * No more read spin loop in NIO when the channel is half closed.
                 *
                 * 关闭channel的input后，如果接收缓冲区有已接收的数据，则将会被丢弃，
                 * 后续再收到新的数据，会对数据进行 ACK，然后悄悄地丢弃。但是reactor还是会触发read事件，只不过此时对channel进行read
                 * 会返回-1。
                 *
                 * 这里和服务端收到fin一样，也是触发read事件，对channel读取会返回-1
                 *
                 * 在连接半关闭的情况下，JDK NIO Selector会不停的通知OP_READ事件活跃直到调用close完全关闭连接,不过JDK这样处理也是合理的
                 * 毕竟半关闭状态连接并没有完全关闭，只要连接没有完全关闭，就不停的通知你，直到关闭连接为止。
                 *
                 * 设置inputClosedSeenErrorOnRead = true 如果下次在接收到数据 就将read事件从reactor上取消掉，停止读取
                 * 触发ChannelInputShutdownReadComplete 表示后续不会在进行读取了 保证只会被触发一次
                 *
                 * */
                //表示此时Channel的读通道已经关闭了，不能再继续响应`OP_READ事件`，
                //因为半关闭状态下，Selector会不停的通知`OP_READ事件`，如果不停无脑响应的话，会造成极大的CPU资源的浪费。
                inputClosedSeenErrorOnRead = true;
                // 在连接半关闭的情况下，JDK NIO Selector会不停的通知OP_READ事件活跃，
                // 当用户处理完ChannelInputShutdownEvent事件后，马上会来到这里。
                // 此时被动关闭方的待处理数据已经ChannelInputShutdownEvent事件中处理完毕并且发送给了对端，
                // 这里会触发ChannelInputShutdownReadComplete事件，用户可以在这个事件中调用close方法关闭连接结束close_wait
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    readPending = false;
                    //如果发生异常时，已经读取到了部分数据，则触发ChannelRead事件
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
                /**
                 * https://github.com/netty/netty/commit/ed0668384b393c3502c2136e3cc412a5c8c9056e
                 * No more read spin loop in NIO when the channel is half closed.
                 * https://www.excentis.com/blog/tcp-half-close-cool-feature-now-broken
                 *
                 *
                 * 这里和服务端收到fin一样，也是触发read事件，对channel读取会返回-1
                 *
                 * close和shutdownOutput均会向对端发送fin，当对端接收到fin报文，内核会返回ack,然后将EOF描述符放入socket的接收缓存区中
                 * 此时socket上的read事件活跃，epoll会通知。但是这里需要注意在这种因为对端关闭连接触发的read事件，epoll会一直通知，也就是说
                 * 这里的read方法会一直不断的执行，空转。直到服务端执行close结束close_wait装填
                 *
                 * close方法不会出现空转的原因是，调用jdk底层的close，会将channel对应的selectionkey从selector上cancel掉，所以
                 * 调用close方法关闭的channel不会一直被通知read事件活跃。
                 *
                 * 而调用shutdownOutput进行半关闭的channel就会一直空转，因为调用jdk底层shutdownOutput方法，不会cancel对应的selectionKey
                 * 这也是合理的，因为调用shutdownOutput在半关闭的状态下，channel还会希望处理读事件，所以不会从selector上cancel。
                 *
                 * 比如：客户端调用shutdownOutput，服务端在这里会调用shutdownInput，服务端channel并不会从reactor上cancel。
                 * 由于selectionKey在半关闭的状态下不会被cancel，而epoll会不停地通知read事件（其实是关闭事件），导致这里的read方法一直空转
                 *
                 * https://stackoverflow.com/questions/52976152/tcp-when-is-epollhup-generated
                 *
                 * 主动关闭方调用close或者shutdownOutput，服务端epoll会触发EPOLLRDHUP事件
                 *  EPOLLRDHUP (since Linux 2.6.17)
                 *               Stream socket peer closed connection, or shut down writing
                 *               half of connection.  (This flag is especially useful for
                 *               writing simple code to detect peer shutdown when using
                 *               edge-triggered monitoring.)
                 *
                 * 在半关闭的状态下 这里的逻辑是当channel的input关闭后，就将read事件取消掉，避免继续read loop导致消耗不必要的CPU消耗
                 * 因为epoll会一直通知，因为不管是任何方向的shutdown,都不会关闭连接，除非调用close。这种情况下epoll认为服务端并没有处理
                 * 关闭连接，所以一直会通知这个read事件
                 * @see SocketHalfClosedTest
                 * */
                clearReadPending();
                return;
            }
            final ChannelPipeline pipeline = pipeline();
            //PooledByteBufAllocator 具体用于实际分配ByteBuf的分配器
            final ByteBufAllocator allocator = config.getAllocator();
            //自适应ByteBuf分配器 AdaptiveRecvByteBufAllocator ,用于动态调节ByteBuf容量
            //需要与具体的ByteBuf分配器配合使用 比如这里的PooledByteBufAllocator
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
                    // -1 表示客户端主动关闭了连接close或者shutdownOutput 这里均会返回-1
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
                //收到RST包时 OP_READ事件活跃
                //当我们正在读取数据的时候，遇到对端发送RST强制关闭连接，就会抛出IOExcetion
                //  发送RST强制关闭连接，这将导致之前已经发送但尚未送达的、或是已经进入对端 receive buffer
                //  但还未被对端应用程序处理的数据被对端无条件丢弃，对端应用程序可能出现异常
                //https://jiroujuan.wordpress.com/2013/09/05/rst-and-exceptions-when-closing-socket-in-java/
                //https://www.mianshigee.com/note/detail/105368qmn/
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
                 * 这里需要注意当设置autoRead = false后，需要将本次接收数据全部接收完毕(readPending = false) 才会removeReadOp
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
