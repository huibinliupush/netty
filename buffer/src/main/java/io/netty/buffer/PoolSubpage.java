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

package io.netty.buffer;

import java.util.concurrent.locks.ReentrantLock;

import static io.netty.buffer.PoolChunk.RUN_OFFSET_SHIFT;
import static io.netty.buffer.PoolChunk.SIZE_SHIFT;
import static io.netty.buffer.PoolChunk.IS_USED_SHIFT;
import static io.netty.buffer.PoolChunk.IS_SUBPAGE_SHIFT;

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    final int elemSize;
    private final int pageShifts;
    private final int runOffset;
    private final int runSize;
    private final long[] bitmap;
    private final int bitmapLength;
    private final int maxNumElems;
    // 该 subPage 管理的 elemSize 对应的 sizeIndex
    final int headIndex;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    // 最近刚刚被释放回 subPage 的内存块 在 bitmap 中的 bitmapIdx
    private int nextAvail;
    private int numAvail;

    final ReentrantLock lock;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int headIndex) {
        chunk = null;
        lock = new ReentrantLock();
        pageShifts = -1;
        runOffset = -1;
        elemSize = -1;
        runSize = -1;
        bitmap = null;
        bitmapLength = -1;
        maxNumElems = 0;
        this.headIndex = headIndex;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int pageShifts, int runOffset, int runSize, int elemSize) {
        // 该 Subpage 管理的 elemSize 对应的 sizeIndex
        this.headIndex = head.headIndex;
        // Subpage 所属 chunk
        this.chunk = chunk;
        // 13
        this.pageShifts = pageShifts;
        // Subpage 在 chunk 中的起始偏移（page为粒度）
        this.runOffset = runOffset;
        // Subpage 大小（字节单位）
        this.runSize = runSize;
        // Subpage 管理的内存块大小
        this.elemSize = elemSize;

        doNotDestroy = true;
        // Subpage 管理的内存块个数
        maxNumElems = numAvail = runSize / elemSize;
        // 一个内存块用 1bit 表示其的分配状态
        // 一个 long 有 64bits, maxNumElems / 64 计算出 bitmap 数组大小（需要几个 long ）
        int bitmapLength = maxNumElems >>> 6;
        if ((maxNumElems & 63) != 0) {
            bitmapLength ++;
        }
        // bitmap 数组长度
        this.bitmapLength = bitmapLength;
        bitmap = new long[bitmapLength];
        //
        nextAvail = 0;

        lock = null;
        // 将该 Subpage 头插法插入到对应的 smallSubpagePools 中
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }
        // subPage 的下一个可用内存块在 bitmap 中的 index
        final int bitmapIdx = getNextAvail();
        // -1 表示 subPage 中所有内存块都已经被分配出去了（没有空闲内存块）
        if (bitmapIdx < 0) {
            // subPage 全部分配完之后，就从 smallSubpagePools 中删除
            removeFromPool(); // Subpage appear to be in an invalid state. Remove to prevent repeated errors.
            throw new AssertionError("No next available bitmap index found (bitmapIdx = " + bitmapIdx + "), " +
                    "even though there are supposed to be (numAvail = " + numAvail + ") " +
                    "out of (maxNumElems = " + maxNumElems + ") available indexes.");
        }
        // 第几个 long
        int q = bitmapIdx >>> 6;
        // long 中的第几个 bit
        int r = bitmapIdx & 63;
        // 内存块必须是空闲的
        assert (bitmap[q] >>> r & 1) == 0;
        // 设置内存块在 bitmap 对应的 bit 的为 1 （已分配）
        bitmap[q] |= 1L << r;

        if (-- numAvail == 0) {
            // subPage 全部分配完之后，就从 smallSubpagePools 中删除
            removeFromPool();
        }

        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use. partial subpage 仍然留在链表中
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released. full subpage 已经从链表中删除
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        // 将内存块在 bitmap 中的 bit 设置为 0（异或相同为 0）
        bitmap[q] ^= 1L << r;
        // 设置 nextAvail，下一次申请的时候优先申请刚刚被释放的内存块
        setNextAvail(bitmapIdx);
        // 当 subPage 全部分配的时候 （numAvail = 0 ），会将 full subPage 从 subPagePool 中删除
        if (numAvail ++ == 0) {
            // 刚刚释放，重新将 subPage 添加回 subPagePool 中,full subPage 变为 partial subpage
            addToPool(head);
            /* When maxNumElems == 1, the maximum numAvail is also 1.
             * Each of these PoolSubpages will go in here when they do free operation.
             * If they return true directly from here, then the rest of the code will be unreachable
             * and they will not actually be recycled. So return true only on maxNumElems > 1. */
            if (maxNumElems > 1) {
                return true;
            }
        }

        if (numAvail != maxNumElems) {
            // partial subpage
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)  empty subpage
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                // 当前 subpage 链表唯一一个节点，我们始终让链表保留一个 subpage
                // 剩下的如果变成 empty subpage 就释放回内存池
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            // 将该 subpage 从链表中删除
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        // 初始为 0
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // ~bits = 0 表示这个 long 表示的 64 个内存块已经全部分配出去了
            // 在 bitmap 中查找第一个还未全部分配出去的 long
            if (~bits != 0) {
                // bits 中还有未分配出去的内存块
                return findNextAvail0(i, bits);
            }
        }
        // subPage 中无内存块可用
        return -1;
    }
    // 计算 subPage 中的下一个可用的内存块在 bitmap 中的 bit index
    private int findNextAvail0(int i, long bits) {
        // i 表示 bitmap 数组的 index,也就是第几个 long
        // i * 64
        final int baseVal = i << 6;
        for (int j = 0; j < 64; j ++) {
            // 从 bits 的第一个 bit 开始，挨个检查是否为 0
            // 查找 bits 中第一个为 0 的 bit 位 j
            if ((bits & 1) == 0) {
                // baseVal + j
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        // subPage 中包含的 page 个数
        int pages = runSize >> pageShifts;
        // 低 32 位保存 bitmapIdx
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) pages << SIZE_SHIFT
               | 1L << IS_USED_SHIFT
               | 1L << IS_SUBPAGE_SHIFT
               | bitmapIdx;
    }

    @Override
    public String toString() {
        final int numAvail;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            numAvail = 0;
        } else {
            final boolean doNotDestroy;
            PoolSubpage<T> head = chunk.arena.smallSubpagePools[headIndex];
            head.lock();
            try {
                doNotDestroy = this.doNotDestroy;
                numAvail = this.numAvail;
            } finally {
                head.unlock();
            }
            if (!doNotDestroy) {
                // Not used for creating the String.
                return "(" + runOffset + ": not in use)";
            }
        }

        return "(" + this.runOffset + ": " + (this.maxNumElems - numAvail) + '/' + this.maxNumElems +
                ", offset: " + this.runOffset + ", length: " + this.runSize + ", elemSize: " + this.elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        return maxNumElems;
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }
        PoolSubpage<T> head = chunk.arena.smallSubpagePools[headIndex];
        head.lock();
        try {
            return numAvail;
        } finally {
            head.unlock();
        }
    }

    @Override
    public int elementSize() {
        return elemSize;
    }

    @Override
    public int pageSize() {
        return 1 << pageShifts;
    }

    boolean isDoNotDestroy() {
        if (chunk == null) {
            // It's the head.
            return true;
        }
        PoolSubpage<T> head = chunk.arena.smallSubpagePools[headIndex];
        head.lock();
        try {
            return doNotDestroy;
        } finally {
            head.unlock();
        }
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }
}
