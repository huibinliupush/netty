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
package io.netty.channel;

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    // Use an initial value that is bigger than the common MTU of 1500
    static final int DEFAULT_INITIAL = 2048;
    static final int DEFAULT_MAXIMUM = 65536;

    //扩容步长
    private static final int INDEX_INCREMENT = 4;
    //缩容步长
    private static final int INDEX_DECREMENT = 1;

    //RecvBuf分配容量表（扩缩容索引表）按照表中记录的容量大小进行扩缩容
    private static final int[] SIZE_TABLE;

    static {
        //初始化RecvBuf容量分配表
        List<Integer> sizeTable = new ArrayList<Integer>();
        //当分配容量小于512时，扩容单位步长为16递增
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        //当分配容量大于512时，扩容单位步长为一倍
        // Suppress a warning since i becomes negative when an integer overflow happens
        for (int i = 512; i > 0; i <<= 1) { // lgtm[java/constant-comparison]
            sizeTable.add(i);
        }

        //初始化RecbBuf扩缩容索引表
        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    /**
     * 按照给定recvbuf容量，在SIZE_TABLE二分查找到最贴近给定size的容量大小（最小 >= size的容量）
     * */
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;//无符号右移，高位始终补0
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        //最小容量在扩缩容索引表中的index
        private final int minIndex;
        //最大容量在扩缩容索引表中的index
        private final int maxIndex;
        //当前容量在扩缩容索引表中的index 初始33 对应容量2048
        private int index;
        //预计下一次分配buffer的容量，初始：2048
        private int nextReceiveBufferSize;
        //是否缩容
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            //在扩缩容索引表中二分查找到最小大于等于initial 的容量
            index = getSizeTableIndex(initial);
            //2048
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            if (bytes == attemptedBytesRead()) {
                // 这里也会扩容，但不会缩容
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            //预计下一次分配buffer的容量，一开始为2048
            // 扩容有两个时机：
            // 1. 在 do while read loop 中每 read 一次都会调用 io.netty.channel.AdaptiveRecvByteBufAllocator.HandleImpl.lastBytesRead 方法判断是否对下一次 read 进行扩容
            // 2. 在结束 do while read loop 之后，最后调用 io.netty.channel.AdaptiveRecvByteBufAllocator.HandleImpl.readComplete 来判断是否对下一轮 read loop 进行扩容
            return nextReceiveBufferSize;
        }

        /**
         * 扩缩容规则
         *
         * actualReadBytes：本次read事件读取的字节数总量
         *
         * 比如：当前index = 33 对应容量为 2048.下一级的缩容容量为1024 index = 32
         *
         * 1. 本次 1024 < actualReadBytes < 2048 表示 当前分配的recvBuf容量正好合适不需要进行扩容缩容
         * 2. actualReadBytes <= 1024 表示 当前分配的容量有点大了，需要进行缩容了，
         * 但是需要两次满足缩容条件才可以进行缩容，且缩容步长为1.缩容比较谨慎
         * 3. actualReadBytes >= 2048 表示 当前分配的recvBuf容量分配小了，需要进行扩容。
         * 满足一次扩容条件就进行扩容，并且扩容步长为4 扩容比较奔放
         *
         * */
        private void record(int actualReadBytes) {
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                if (decreaseNow) {
                    index = max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
                //是否对recvbuf进行扩容缩容
                record(totalBytesRead());
        }
    }
    //默认最小容量为64  在SIZE_TABLE中二分查找最小 >= 64的容量索引 ：3
    private final int minIndex;
    //默认最大容量为65535  在SIZE_TABLE中二分查找最大 <= 65535的容量索引 ：38
    private final int maxIndex;
    //初始容量为2048
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        //计算minIndex maxIndex
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        //在SIZE_TABLE中二分查找最小 >= minimum的容量索引 ：3
        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        //在SIZE_TABLE中二分查找最大 <= maximum的容量索引 ：38
        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
