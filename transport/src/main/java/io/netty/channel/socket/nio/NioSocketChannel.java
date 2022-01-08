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
package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.socket.DefaultSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.SuppressJava6Requirement;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.Executor;

import static io.netty.channel.internal.ChannelUtils.MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD;

/**
 * {@link io.netty.channel.socket.SocketChannel} which uses NIO selector based implementation.
 */
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioSocketChannel.class);
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();

    private static SocketChannel newSocket(SelectorProvider provider) {
        try {
            /**
             *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
             *  {@link SelectorProvider#provider()} which is called by each SocketChannel.open() otherwise.
             *
             *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
             */
            return provider.openSocketChannel();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    private final SocketChannelConfig config;

    /**
     * Create a new instance
     */
    public NioSocketChannel() {
        this(DEFAULT_SELECTOR_PROVIDER);
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}.
     */
    public NioSocketChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    /**
     * Create a new instance using the given {@link SocketChannel}.
     */
    public NioSocketChannel(SocketChannel socket) {
        this(null, socket);
    }

    /**
     * Create a new instance
     *
     * @param parent    the {@link Channel} which created this instance or {@code null} if it was created by the user
     * @param socket    the {@link SocketChannel} which will be used
     */
    public NioSocketChannel(Channel parent, SocketChannel socket) {
        super(parent, socket);
        config = new NioSocketChannelConfig(this, socket.socket());
    }

    @Override
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override
    public SocketChannelConfig config() {
        return config;
    }

    @Override
    protected SocketChannel javaChannel() {
        return (SocketChannel) super.javaChannel();
    }

    @Override
    public boolean isActive() {
        SocketChannel ch = javaChannel();
        return ch.isOpen() && ch.isConnected();
    }

    @Override
    public boolean isOutputShutdown() {
        return javaChannel().socket().isOutputShutdown() || !isActive();
    }

    @Override
    public boolean isInputShutdown() {
        return javaChannel().socket().isInputShutdown() || !isActive();
    }

    @Override
    public boolean isShutdown() {
        Socket socket = javaChannel().socket();
        return socket.isInputShutdown() && socket.isOutputShutdown() || !isActive();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    @UnstableApi
    @Override
    /**
     *
     *  注意半关闭shutdownOutput不会将channel从reactor上deRegister，也就是说不会清理selectionKey，close方法会清理
     *
     *  所以需要定时清理selectionKeySet中的失效selectKey（半关闭引起）
     *
     *  @see NioEventLoop#cancel(java.nio.channels.SelectionKey)
     *  @see io.netty.channel.nio.NioEventLoop#processSelectedKey(java.nio.channels.SelectionKey, io.netty.channel.nio.AbstractNioChannel)
     * */
    protected final void doShutdownOutput() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownOutput();
        } else {
            javaChannel().socket().shutdownOutput();
        }
    }

    /**
     * close方法和shutdown方法都会发送fin
     *
     * 关闭连接的「写」这个方向，这就是常被称为「半关闭」的连接。如果发送缓冲区还有未发送的数据，将被立即发送出去，并发送一个 FIN 报文给对端
     *
     * 注意半关闭shutdownOutput不会将channel从reactor上deRegister，也就是说不会清理selectionKey，close方法会清理
     *
     * 所以需要定时清理selectionKeySet中的失效selectKey（半关闭引起）
     *
     * @see NioEventLoop#cancel(java.nio.channels.SelectionKey)
     * @see io.netty.channel.nio.NioEventLoop#processSelectedKey(java.nio.channels.SelectionKey, io.netty.channel.nio.AbstractNioChannel)
     * */
    @Override
    public ChannelFuture shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override
    public ChannelFuture shutdownOutput(final ChannelPromise promise) {
        final EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
                }
            });
        }
        return promise;
    }

    /**
     * 关闭连接的「读」这个方向，注意不能读的意思内核不能再往内核缓冲区中增加新的内容。已经在内核缓冲区中的内容，用户态依然能够读取到。
     * 并且后续再收到新的数据，会对数据进行 ACK，然后悄悄地丢弃。也就是说，对端还是会接收到 ACK，在这种情况下根本不知道数据已经被丢弃了。
     *
     * */
    @Override
    public ChannelFuture shutdownInput() {
        //半关闭：关闭接收方向的通道，但发送方向的通道还没关闭
        return shutdownInput(newPromise());
    }

    @Override
    protected boolean isInputShutdown0() {
        return isInputShutdown();
    }

    @Override
    public ChannelFuture shutdownInput(final ChannelPromise promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            shutdownInput0(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    shutdownInput0(promise);
                }
            });
        }
        return promise;
    }

    @Override
    public ChannelFuture shutdown() {
        return shutdown(newPromise());
    }

    @Override
    public ChannelFuture shutdown(final ChannelPromise promise) {
        ChannelFuture shutdownOutputFuture = shutdownOutput();
        if (shutdownOutputFuture.isDone()) {
            shutdownOutputDone(shutdownOutputFuture, promise);
        } else {
            shutdownOutputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture shutdownOutputFuture) throws Exception {
                    shutdownOutputDone(shutdownOutputFuture, promise);
                }
            });
        }
        return promise;
    }

    private void shutdownOutputDone(final ChannelFuture shutdownOutputFuture, final ChannelPromise promise) {
        ChannelFuture shutdownInputFuture = shutdownInput();
        if (shutdownInputFuture.isDone()) {
            shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
        } else {
            shutdownInputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture shutdownInputFuture) throws Exception {
                    shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
                }
            });
        }
    }

    private static void shutdownDone(ChannelFuture shutdownOutputFuture,
                                     ChannelFuture shutdownInputFuture,
                                     ChannelPromise promise) {
        Throwable shutdownOutputCause = shutdownOutputFuture.cause();
        Throwable shutdownInputCause = shutdownInputFuture.cause();
        if (shutdownOutputCause != null) {
            if (shutdownInputCause != null) {
                logger.debug("Exception suppressed because a previous exception occurred.",
                        shutdownInputCause);
            }
            promise.setFailure(shutdownOutputCause);
        } else if (shutdownInputCause != null) {
            promise.setFailure(shutdownInputCause);
        } else {
            promise.setSuccess();
        }
    }
    private void shutdownInput0(final ChannelPromise promise) {
        try {
            //调用底层JDK socketChannel关闭接收方向的通道
            shutdownInput0();
            promise.setSuccess();
        } catch (Throwable t) {
            promise.setFailure(t);
        }
    }

    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    private void shutdownInput0() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            //调用底层JDK socketChannel关闭接收方向的通道
            javaChannel().shutdownInput();
        } else {
            javaChannel().socket().shutdownInput();
        }
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        doBind0(localAddress);
    }

    private void doBind0(SocketAddress localAddress) throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            SocketUtils.bind(javaChannel(), localAddress);
        } else {
            SocketUtils.bind(javaChannel().socket(), localAddress);
        }
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (localAddress != null) {
            doBind0(localAddress);
        }

        boolean success = false;
        try {
            boolean connected = SocketUtils.connect(javaChannel(), remoteAddress);
            if (!connected) {
                selectionKey().interestOps(SelectionKey.OP_CONNECT);
            }
            success = true;
            return connected;
        } finally {
            if (!success) {
                doClose();
            }
        }
    }

    @Override
    protected void doFinishConnect() throws Exception {
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        super.doClose();
        javaChannel().close();
    }

    @Override
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
        final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
        allocHandle.attemptedBytesRead(byteBuf.writableBytes());
        //读到EOF后，这里会返回-1
        //如果在读取的过程中遇到EOF,或者遇到RST包，将会抛出IOException
        return byteBuf.writeBytes(javaChannel(), allocHandle.attemptedBytesRead());
    }

    @Override
    protected int doWriteBytes(ByteBuf buf) throws Exception {
        final int expectedWrittenBytes = buf.readableBytes();
        return buf.readBytes(javaChannel(), expectedWrittenBytes);
    }

    @Override
    protected long doWriteFileRegion(FileRegion region) throws Exception {
        final long position = region.transferred();
        return region.transferTo(javaChannel(), position);
    }

    private void adjustMaxBytesPerGatheringWrite(int attempted, int written, int oldMaxBytesPerGatheringWrite) {
        // By default we track the SO_SNDBUF when ever it is explicitly set. However some OSes may dynamically change
        // SO_SNDBUF (and other characteristics that determine how much data can be written at once) so we should try
        // make a best effort to adjust as OS behavior changes.

        /**
         * 由于操作系统会动态调整`SO_SNDBUF`的大小，所以这里netty也需要根据操作系统的动态调整做出相应的适配
         *
         * 如果尝试写的都能写进socket中，（attempted为channelOutboundBuffer中待发送数据的总大小，written为本次writeLoop实际写入socket的大小）
         * 就要尝试去写更多，增大下一次writeLoop要写入的数据量。增大多少呢？
         * 如果本次尝试写入的数据都写进socket中，那么attempted扩大两倍后 超过了 初始化时指定的 SO_SNDBUF * 2的最大写入量
         * 那么就将最大写入量更新为 本次attempted的两倍，这样在下次writeLoop时，就会从channelOutboundBuffer中获取两倍大小的待写数据
         *
         * 如果本次写入的数据还不及尝试写入数据的 1/2。说明socket缓冲区快满了，下一次writeLoop就不要写这么多了 尝试减少下次写入的量
         * 将下次writeLoop要写入的数据减小为attempted的两倍
         *
         * */
        if (attempted == written) {
            if (attempted << 1 > oldMaxBytesPerGatheringWrite) {
                ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted << 1);
            }
        } else if (attempted > MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD && written < attempted >>> 1) {
            ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted >>> 1);
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        SocketChannel ch = javaChannel();
        //最大写入次数 默认为16 目的是为了保证SubReactor可以平均的处理注册其上的所有Channel
        int writeSpinCount = config().getWriteSpinCount();
        do {
            if (in.isEmpty()) {
                // All written so clear OP_WRITE
                // 如果全部数据已经写完 则移除OP_WRITE事件并直接退出writeLoop
                // 如果本次flush操作是由reactor通知的write事件触发的，则在写完后需要清除write事件，否则会一直触发通知（因为socket目前是可写的）
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }

            // Ensure the pending writes are made of ByteBufs only.
            //  SO_SNDBUF设置的发送缓冲区大小 * 2 作为 最大写入字节数  293976 = 146988 << 2
            int maxBytesPerGatheringWrite = ((NioSocketChannelConfig) config).getMaxBytesPerGatheringWrite();

            // 将ChannelOutboundBuffer中缓存的DirectBuffer转换成JDK NIO 的 ByteBuffer
            ByteBuffer[] nioBuffers = in.nioBuffers(1024, maxBytesPerGatheringWrite);

            // ChannelOutboundBuffer中总共的DirectBuffer数
            int nioBufferCnt = in.nioBufferCount();

            // Always use nioBuffers() to workaround data-corruption.
            // See https://github.com/netty/netty/issues/2761
            switch (nioBufferCnt) {
                case 0:
                    // We have something else beside ByteBuffers to write so fallback to normal writes. 比如fileRegion

                    /**
                     *
                     * 当ChannelOutboundBuffer中缓存的msg类型不是ByteBuf时，nioBufferCnt会为0 比如FileRegion(零拷贝传输文件)
                     * 当在doWrite0方法中发现Socket已经写不进去了 则返回WRITE_STATUS_SNDBUF_FULL = Integer.MAX_VALUE
                     * 这样在这里直接会导致 writeSpinCount < 0 退出writeLoop，在incompleteWrite方法中注册OP_WRITE事件
                     *
                     * 这里主要是针对 网络传输文件数据 的处理 FileRegion
                     */

                    writeSpinCount -= doWrite0(in);
                    break;
                case 1: {
                    // Only one ByteBuf so use non-gathering write
                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    ByteBuffer buffer = nioBuffers[0];
                    int attemptedBytes = buffer.remaining();
                    final int localWrittenBytes = ch.write(buffer);
                    if (localWrittenBytes <= 0) {
                        //如果当前Socket发送缓冲区满了写不进去了，则注册OP_WRITE事件，等待Socket发送缓冲区可写时 在写
                        // SubReactor在处理OP_WRITE事件时，直接调用flush方法
                        incompleteWrite(true);
                        return;
                    }
                    //根据当前实际写入情况调整 maxBytesPerGatheringWrite数值
                    adjustMaxBytesPerGatheringWrite(attemptedBytes, localWrittenBytes, maxBytesPerGatheringWrite);
                    //如果ChannelOutboundBuffer中的某个Entry被全部写入 则删除该Entry
                    // 如果Entry被写入了一部分 还有一部分未写入  则更新Entry中的readIndex 等待下次writeLoop继续写入
                    in.removeBytes(localWrittenBytes);
                    --writeSpinCount;
                    break;
                }
                default: {
                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    // We limit the max amount to int above so cast is safe

                    // ChannelOutboundBuffer中总共待写入数据的字节数
                    long attemptedBytes = in.nioBufferSize();
                    //批量写入
                    final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt);
                    if (localWrittenBytes <= 0) {
                        incompleteWrite(true);
                        return;
                    }
                    // Casting to int is safe because we limit the total amount of data in the nioBuffers to int above.
                    //根据实际写入情况调整一次写入数据大小的最大值
                    // maxBytesPerGatheringWrite决定每次可以从channelOutboundBuffer中获取多少发送数据
                    adjustMaxBytesPerGatheringWrite((int) attemptedBytes, (int) localWrittenBytes,
                            maxBytesPerGatheringWrite);
                    //移除全部写完的BUffer，如果只写了部分数据则更新buffer的readerIndex，下一个writeLoop写入
                    in.removeBytes(localWrittenBytes);
                    --writeSpinCount;
                    break;
                }
            }
        } while (writeSpinCount > 0);


        /**
         * 处理数据未写完，但是writeLoop已经写满16次的情况，SubReactor需要停止继续写入，转而去处理其他Channel上的IO事件
         *
         * writeSpinCount < 0出现的情况：
         * 当Netty服务需要 网络传输文件数据 时，ChannelOoutboundBuffer中的msg类型为FileRegion（用于零拷贝传输文件数据）
         *
         * 这里的nioBufferCnt会是0 进入doWrite0方法处理文件传输（文件数据写入Socket）,如果此时Socket缓冲区写不下时，
         * 会导致writeSpinCount < 0，直接这里会注册OP_WRITE事件
         * */
        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioSocketChannelUnsafe();
    }




    /**
     *
     *     SO_LINGER
     *     Sets or gets the SO_LINGER option.  The argument is a
     *     linger structure.
     *
     *     struct linger {
     *         int l_onoff;    linger active
     *         int l_linger;   how many seconds to linger for
     *     };
     *
     *     When enabled, a close(2) or shutdown(2) will not return
     *     until all queued messages for the socket have been
     *     successfully sent or the linger timeout has been reached.
     *             Otherwise, the call returns immediately and the closing is
     *     done in the background.  When the socket is closed as part
     *     of exit(2), it always lingers in the background.
     *
     * */


    /**
     *
     *   在默认情况下,当调用close关闭socke的使用,close会立即返回,但是,如果send buffer中还有数据,系统会试着先把send buffer中的数据发送出去
     *   SO_LINGER选项则是用来修改这种默认操作
     *   影响close方法和shutdown方法的返回，l_onoff非零开启so_linger，l_linger逗留时间大于0
     *   则调用close或者shutdown不会立即返回，而是需要等待socket对应的发送缓冲区的数据发送完毕并收到对应的ack
     *   或者逗留时间超时，关闭方法才会返回。
     *   https://blog.csdn.net/songchuwang1868/article/details/90369445
     *
     *   在 Linux 上，设置 SO_LINGER 且使用非零值延迟时间会导致应用程序阻塞，即使程序创建的是一个 non-blocking socket。
     *   由于 Linux 的实现问题，即使程序使用的 non-blocking sockets，lingering 也会导致程序阻塞，
     *   自行编写 socket 程序使用 lingering 的配置应当慎重，至少不应该为了避免 TIME_WAIT 堆积而使用 lingering。
     *   https://www.starduster.me/2019/07/06/socket-lingering-and-closing/
     *
     *   linux开启SO_LINGER时，如果设置l_linger为非0， 不管是阻塞socket，非阻塞socket， 在这里都会发生阻塞， 而并不是UNP所讲到的( 非阻塞socket会立即返回EWOULDBLOCK)
     * */

    /**
     *
     * so_linger: https://stackoverflow.com/questions/3757289/when-is-tcp-option-so-linger-0-required
     *            http://www.serverframework.com/asynchronousevents/2011/01/time-wait-and-its-design-implications-for-protocols-and-scalable-servers.html
     *
     * SO_LINGER设置为0：There's another way to terminate a TCP connection and that's by aborting the connection and sending an RST
     * rather than a FIN. This is usually achieved by setting the SO_LINGER socket option to 0.
     * This causes pending data to be discarded and the connection to be aborted with an RST rather than
     * for the pending data to be transmitted and the connection closed cleanly with a FIN.
     * It's important to realise that when a connection is aborted any data that might be in flow
     * between the peers is discarded and the RST is delivered straight away; usually as an error which represents
     * the fact that the "connection has been reset by the peer". The remote peer knows that the connection was aborted and
     * neither peer enters TIME_WAIT.
     *
     * 发送RST强制关闭连接，这将导致之前已经发送但尚未送达的、或是已经进入对端 receive buffer
     * 但还未被对端应用程序处理的数据被对端无条件丢弃，对端应用程序可能出现异常
     *
     * 那么调用 close 后，会立该发送一个 RST 标志给对端，该 TCP 连接将跳过四次挥手，也就跳过了 TIME_WAIT 状态，直接关闭。
     * 主动发FIN包方必然会进入TIME_WAIT状态，除非不发送FIN而直接以发送RST结束连接。
     *
     * 关闭连接的最佳实践：
     *
     * The best way to do this is to never initiate an active close from the server,
     * no matter what the reason. If your peer times out, abort the connection with an RST rather than closing it.
     * If your peer sends invalid data, abort the connection, etc.
     * The idea being that if your server never initiates an active close it can never accumulate TIME_WAIT sockets and
     * therefore will never suffer from the scalability problems that they cause.
     * Although it's easy to see how you can abort connections when error situations occur what about normal connection
     * termination? Ideally you should design into your protocol a way for the server to tell the client that it should
     * disconnect, rather than simply having the server instigate an active close.
     * So if the server needs to terminate a connection the server sends an application level "we're done" message
     * which the client takes as a reason to close the connection.
     * If the client fails to close the connection in a reasonable time then the server aborts the connection.
     *
     * */
    private final class NioSocketChannelUnsafe extends NioByteUnsafe {
        @Override
        protected Executor prepareToClose() {
            try {
                if (javaChannel().isOpen() && config().getSoLinger() > 0) {
                    // We need to cancel this key of the channel so we may not end up in a eventloop spin
                    // because we try to read or write until the actual close happens which may be later due
                    // SO_LINGER handling.
                    // See https://github.com/netty/netty/issues/4449

                    //在设置SO_LINGER后，channel会延时关闭，在延时期间我们仍然可以进行读写，这样会导致io线程eventloop不断的循环浪费cpu资源
                    //所以需要在延时关闭期间 将channel注册的事件全部取消。
                    //https://www.shuzhiduo.com/A/E35pwLL8Jv/

                    // We need to remove all registered events for a Channel from the EventLoop
                    // before doing the actual close to ensure we not produce a cpu spin
                    // when the actual close operation is delayed or executed outside of the EventLoop.
                    //
                    // Modifications:
                    //
                    // Deregister for events for NIO and EPOLL socket implementations when SO_LINGER is used.
                    doDeregister();

                    /**
                     * 该executor用于执行channel关闭的任务。
                     * 注意channel关闭的任务不能在reactor中执行，因为reactor负责多个channel的IO事件的处理，不能因为执行close任务
                     * 耽误其他channel上的IO事件处理,因为这里用户设置了SO_LINGER,关闭操作会逗留，不能影响Reactor执行其他channel上的
                     * IO事件
                     *
                     * 设置了SO_LINGER,不管是阻塞socket还是非阻塞socket，在关闭的时候都会发生阻塞，所以这里不能使用Reactor线程来
                     * 执行关闭任务，否则Reactor线程就会被阻塞。
                     * */
                    return GlobalEventExecutor.INSTANCE;
                }
            } catch (Throwable ignore) {
                // Ignore the error as the underlying channel may be closed in the meantime and so
                // getSoLinger() may produce an exception. In this case we just return null.
                // See https://github.com/netty/netty/issues/4449
            }
            //在没有设置SO_LINGER的情况下，可以使用Reactor线程来执行关闭任务
            return null;
        }
    }

    private final class NioSocketChannelConfig extends DefaultSocketChannelConfig {
        //293976 = 146988 << 2
        //SO_SNDBUF设置的发送缓冲区大小 * 2 作为 最大写入字节数
        //最小值为2048  see io.netty.channel.socket.nio.NioSocketChannel.adjustMaxBytesPerGatheringWrite
        private volatile int maxBytesPerGatheringWrite = Integer.MAX_VALUE;
        private NioSocketChannelConfig(NioSocketChannel channel, Socket javaSocket) {
            super(channel, javaSocket);
            calculateMaxBytesPerGatheringWrite();
        }

        @Override
        protected void autoReadCleared() {
            clearReadPending();
        }

        @Override
        public NioSocketChannelConfig setSendBufferSize(int sendBufferSize) {
            super.setSendBufferSize(sendBufferSize);
            calculateMaxBytesPerGatheringWrite();
            return this;
        }

        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.setOption(jdkChannel(), (NioChannelOption<T>) option, value);
            }
            return super.setOption(option, value);
        }

        @Override
        public <T> T getOption(ChannelOption<T> option) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.getOption(jdkChannel(), (NioChannelOption<T>) option);
            }
            return super.getOption(option);
        }

        @Override
        public Map<ChannelOption<?>, Object> getOptions() {
            if (PlatformDependent.javaVersion() >= 7) {
                return getOptions(super.getOptions(), NioChannelOption.getOptions(jdkChannel()));
            }
            return super.getOptions();
        }

        void setMaxBytesPerGatheringWrite(int maxBytesPerGatheringWrite) {
            this.maxBytesPerGatheringWrite = maxBytesPerGatheringWrite;
        }

        int getMaxBytesPerGatheringWrite() {
            return maxBytesPerGatheringWrite;
        }

        private void calculateMaxBytesPerGatheringWrite() {
            // Multiply by 2 to give some extra space in case the OS can process write data faster than we can provide.
            // 293976 = 146988 << 1
            // SO_SNDBUF设置的发送缓冲区大小 * 2 作为 最大写入字节数
            int newSendBufferSize = getSendBufferSize() << 1;
            if (newSendBufferSize > 0) {
                setMaxBytesPerGatheringWrite(newSendBufferSize);
            }
        }

        private SocketChannel jdkChannel() {
            return ((NioSocketChannel) channel).javaChannel();
        }
    }
}
