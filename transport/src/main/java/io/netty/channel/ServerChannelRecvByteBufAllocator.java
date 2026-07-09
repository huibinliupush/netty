/*
 * Copyright 2021 The Netty Project
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
package io.netty.channel;

/**
 * {@link MaxMessagesRecvByteBufAllocator} implementation which should be used for {@link ServerChannel}s.
 */
public final class ServerChannelRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {
    public ServerChannelRecvByteBufAllocator() {
        // 后续会在 io.netty.channel.DefaultChannelConfig.setRecvByteBufAllocator(io.netty.channel.RecvByteBufAllocator, io.netty.channel.ChannelMetadata)
        // 中将 maxMessagesPerRead 设置为 16
        super(1, true);
    }

    @Override
    public Handle newHandle() {
        return new MaxMessageHandle() {
            @Override
            public int guess() {
                return 128;
            }
        };
    }
}
