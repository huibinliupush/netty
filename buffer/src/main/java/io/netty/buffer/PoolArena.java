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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static io.netty.buffer.PoolChunk.isSubpage;
import static java.lang.Math.max;

abstract class PoolArena<T> implements PoolArenaMetric {
    private static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    enum SizeClass {
        Small,
        Normal
    }

    final PooledByteBufAllocator parent;

    final PoolSubpage<T>[] smallSubpagePools;

    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations（申请和释放对应内存规格的次数）
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    // 总共 huge 内存规格的分配次数（一直累加）
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    // 当前分配的 huge 内存规格所占的字节数（free 的时候还会减掉）
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    // 该 PoolArena 被多少个线程绑定
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    private final ReentrantLock lock = new ReentrantLock();

    final SizeClasses sizeClass;

    protected PoolArena(PooledByteBufAllocator parent, SizeClasses sizeClass) {
        assert null != sizeClass;
        this.parent = parent;
        this.sizeClass = sizeClass;
        // small 内存规格将会在这里分配 —— 类似 kmalloc
        // 每一种 small 内存规格都会对应一个 PoolSubpage （类似 slab）
        smallSubpagePools = newSubpagePoolArray(sizeClass.nSubpages);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            // smallSubpagePools 数组中的每一项是一个 PoolSubpage 结构的双向链表
            // 双向链表的头结点 SubpagePoolHead
            smallSubpagePools[i] = newSubpagePoolHead(i);
        }
        // 创建不同内存利用率的 PoolChunkList
        // q100 的 maxUsage 设置为 Integer.MAX_VALUE 的目的就是让内存使用率 100% 的 PoolChunk 一直停留在 q100 中，永远不会向后移动
        // freeBytes 永远不会低于 freeMinThreshold，不会触发向后移动条件
        // see : io.netty.buffer.PoolChunkList.allocate
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, sizeClass.chunkSize);// [100 , 2147483647]
        q075 = new PoolChunkList<T>(this, q100, 75, 100, sizeClass.chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, sizeClass.chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, sizeClass.chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, sizeClass.chunkSize);
        // qInit 的 minUsage 设置为 Integer.MIN_VALUE 的目的是始终使其 freeBytes 不可能大于 freeMaxThreshold，不会触发向前移动条件
        // 这样当 qInit 中的 PoolChunk 的使用率为 0 % 时，会永远停留在 qInit 中不做任何改变。
        // see : io.netty.buffer.PoolChunkList.free
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, sizeClass.chunkSize);// [-2147483648 , 25]

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    private PoolSubpage<T> newSubpagePoolHead(int index) {
        PoolSubpage<T> head = new PoolSubpage<T>(index);
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        // 从对象池中获取一个 PooledByteBuf 对象，这里设置 maxCapacity
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        // 获取 reqCapacity 在 sizeIdx2sizeTab 中的 index
        final int sizeIdx = sizeClass.size2SizeIdx(reqCapacity);
        // [16B , 28K] 之间是 small 规格的内存
        if (sizeIdx <= sizeClass.smallMaxSizeIdx) {
            tcacheAllocateSmall(cache, buf, reqCapacity, sizeIdx);
        } else if (sizeIdx < sizeClass.nSizes) {
            // [32K , 4M] 之间是 normal 规格的内存
            tcacheAllocateNormal(cache, buf, reqCapacity, sizeIdx);
        } else {
            int normCapacity = sizeClass.directMemoryCacheAlignment > 0
                    ? sizeClass.normalizeSize(reqCapacity) : reqCapacity;
            // Huge allocations are never served via the cache so just call allocateHuge
            // huge 内存规格的直接向操作系统申请，不经过 cache 也不经过内存池
            allocateHuge(buf, normCapacity);
        }
    }

    private void tcacheAllocateSmall(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                     final int sizeIdx) {

        if (cache.allocateSmall(this, buf, reqCapacity, sizeIdx)) {
            // was able to allocate out of the cache so move on
            return;
        }

        /*
         * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
         * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
         */
        final PoolSubpage<T> head = smallSubpagePools[sizeIdx];
        final boolean needsNormalAllocation;
        head.lock();
        try {
            final PoolSubpage<T> s = head.next;
            // sizeIdx 对应的 SubpagePools(双向链表)如果为空，表示现在还有对应的 Subpage
            // needsNormalAllocation = true
            needsNormalAllocation = s == head;
            if (!needsNormalAllocation) {
                assert s.doNotDestroy && s.elemSize == sizeClass.sizeIdx2size(sizeIdx) : "doNotDestroy=" +
                        s.doNotDestroy + ", elemSize=" + s.elemSize + ", sizeIdx=" + sizeIdx;
                long handle = s.allocate();
                assert handle >= 0;
                s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity, cache);
            }
        } finally {
            head.unlock();
        }

        if (needsNormalAllocation) {
            lock();
            try {
                allocateNormal(buf, reqCapacity, sizeIdx, cache);
            } finally {
                unlock();
            }
        }
        // 增加 allocationsSmall 计数
        incSmallAllocation();
    }

    private void tcacheAllocateNormal(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                      final int sizeIdx) {
        if (cache.allocateNormal(this, buf, reqCapacity, sizeIdx)) {
            // was able to allocate out of the cache so move on
            return;
        }
        lock();
        try {
            allocateNormal(buf, reqCapacity, sizeIdx, cache);
            ++allocationsNormal;
        } finally {
            unlock();
        }
    }

    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache threadCache) {
        assert lock.isHeldByCurrentThread();
        if (q050.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q025.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q000.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            qInit.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q075.allocate(buf, reqCapacity, sizeIdx, threadCache)) {
            return;
        }

        // Add a new chunk.
        PoolChunk<T> c = newChunk(sizeClass.pageSize, sizeClass.nPSizes, sizeClass.pageShifts, sizeClass.chunkSize);
        boolean success = c.allocate(buf, reqCapacity, sizeIdx, threadCache);
        assert success;
        qInit.add(c);
    }

    private void incSmallAllocation() {
        allocationsSmall.increment();
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        // 更新 pinnedBytes 计数
        chunk.decrementPinnedMemory(normCapacity);
        // Huge 规格的内存块直接释放，非池化管理
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            // 直接将 chunk 的内存释放会 OS
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            // 获取内存块的规格 small ? normal ?
            SizeClass sizeClass = sizeClass(handle);
            // 先释放回对应的 PoolThreadCache 中
            // cache.add 返回 false 表示缓存满了，添加失败。或者对应内存规格缓存结构为空（不缓存），或者对应的 PoolThreadCache 已经被释放（thread exit）
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }
            // 如果缓存失败，则释放回内存池
            freeChunk(chunk, handle, normCapacity, sizeClass, nioBuffer, false);
        }
    }

    private static SizeClass sizeClass(long handle) {
        return isSubpage(handle) ? SizeClass.Small : SizeClass.Normal;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, int normCapacity, SizeClass sizeClass, ByteBuffer nioBuffer,
                   boolean finalizer) {
        final boolean destroyChunk;
        lock();
        try {
            // We only call this if freeChunk is not called because of the PoolThreadCache finalizer as otherwise this
            // may fail due lazy class-loading in for example tomcat.
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    default:
                        throw new Error();
                }
            }
            // chunk 的 usage 为 0 了，就需要被删除，销毁，并释放 chunk 内存
            destroyChunk = !chunk.parent.free(chunk, handle, normCapacity, nioBuffer);
        } finally {
            unlock();
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity) {
        assert newCapacity >= 0 && newCapacity <= buf.maxCapacity();

        final int oldCapacity;
        final PoolChunk<T> oldChunk;
        final ByteBuffer oldNioBuffer;
        final long oldHandle;
        final T oldMemory;
        final int oldOffset;
        final int oldMaxLength;
        final PoolThreadCache oldCache;

        // We synchronize on the ByteBuf itself to ensure there is no "concurrent" reallocations for the same buffer.
        // We do this to ensure the ByteBuf internal fields that are used to allocate / free are not accessed
        // concurrently. This is important as otherwise we might end up corrupting our internal state of our data
        // structures.
        //
        // Also note we don't use a Lock here but just synchronized even tho this might seem like a bad choice for Loom.
        // This is done to minimize the overhead per ByteBuf. The time this would block another thread should be
        // relative small and so not be a problem for Loom.
        // See https://github.com/netty/netty/issues/13467
        synchronized (buf) {
            oldCapacity = buf.length;
            if (oldCapacity == newCapacity) {
                return;
            }

            oldChunk = buf.chunk;
            oldNioBuffer = buf.tmpNioBuf;
            oldHandle = buf.handle;
            oldMemory = buf.memory;
            oldOffset = buf.offset;
            oldMaxLength = buf.maxLength;
            oldCache = buf.cache;

            // This does not touch buf's reader/writer indices
            allocate(parent.threadCache(), buf, newCapacity);
        }
        int bytesToCopy;
        if (newCapacity > oldCapacity) {
            bytesToCopy = oldCapacity;
        } else {
            buf.trimIndicesToCapacity(newCapacity);
            bytesToCopy = newCapacity;
        }
        memoryCopy(oldMemory, oldOffset, buf, bytesToCopy);
        free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, oldCache);
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return 0;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return Collections.emptyList();
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        lock();
        try {
            allocsNormal = allocationsNormal;
        } finally {
            unlock();
        }
        return allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return 0;
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public long numNormalAllocations() {
        lock();
        try {
            return allocationsNormal;
        } finally {
            unlock();
        }
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        lock();
        try {
            deallocs = deallocationsSmall + deallocationsNormal;
        } finally {
            unlock();
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public long numTinyDeallocations() {
        return 0;
    }

    @Override
    public long numSmallDeallocations() {
        lock();
        try {
            return deallocationsSmall;
        } finally {
            unlock();
        }
    }

    @Override
    public long numNormalDeallocations() {
        lock();
        try {
            return deallocationsNormal;
        } finally {
            unlock();
        }
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        lock();
        try {
            val += allocationsNormal - (deallocationsSmall + deallocationsNormal);
        } finally {
            unlock();
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return 0;
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        lock();
        try {
            val = allocationsNormal - deallocationsNormal;
        } finally {
            unlock();
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        lock();
        try {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        } finally {
            unlock();
        }
        return max(0, val);
    }

    /**
     * Return an estimate of the number of bytes that are currently pinned to buffer instances, by the arena. The
     * pinned memory is not accessible for use by any other allocation, until the buffers using have all been released.
     */
    public long numPinnedBytes() {
        long val = activeBytesHuge.value(); // Huge chunks are exact-sized for the buffers they were allocated to.
        for (int i = 0; i < chunkListMetrics.size(); i++) {
            for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                val += ((PoolChunk<?>) m).pinnedBytes();
            }
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, PooledByteBuf<T> dst, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public String toString() {
        lock();
        try {
            StringBuilder buf = new StringBuilder()
                    .append("Chunk(s) at 0~25%:")
                    .append(StringUtil.NEWLINE)
                    .append(qInit)
                    .append(StringUtil.NEWLINE)
                    .append("Chunk(s) at 0~50%:")
                    .append(StringUtil.NEWLINE)
                    .append(q000)
                    .append(StringUtil.NEWLINE)
                    .append("Chunk(s) at 25~75%:")
                    .append(StringUtil.NEWLINE)
                    .append(q025)
                    .append(StringUtil.NEWLINE)
                    .append("Chunk(s) at 50~100%:")
                    .append(StringUtil.NEWLINE)
                    .append(q050)
                    .append(StringUtil.NEWLINE)
                    .append("Chunk(s) at 75~100%:")
                    .append(StringUtil.NEWLINE)
                    .append(q075)
                    .append(StringUtil.NEWLINE)
                    .append("Chunk(s) at 100%:")
                    .append(StringUtil.NEWLINE)
                    .append(q100)
                    .append(StringUtil.NEWLINE)
                    .append("small subpages:");
            appendPoolSubPages(buf, smallSubpagePools);
            buf.append(StringUtil.NEWLINE);
            return buf.toString();
        } finally {
            unlock();
        }
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head || head.next == null) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            while (s != null) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, SizeClasses sizeClass) {
            super(parent, sizeClass);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(
                    this, null, newByteArray(chunkSize), pageSize, pageShifts, chunkSize, maxPageIdx);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, null, newByteArray(capacity), capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, PooledByteBuf<byte[]> dst, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst.memory, dst.offset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, SizeClasses sizeClass) {
            super(parent, sizeClass);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxPageIdx,
            int pageShifts, int chunkSize) {
            if (sizeClass.directMemoryCacheAlignment == 0) {
                // 分配一个 4M 大小的 NoCleanerDirectByteBuffer
                ByteBuffer memory = allocateDirect(chunkSize);
                return new PoolChunk<ByteBuffer>(this, memory, memory, pageSize, pageShifts,
                        chunkSize, maxPageIdx);
            }

            final ByteBuffer base = allocateDirect(chunkSize + sizeClass.directMemoryCacheAlignment);
            // 将 ByteBuffer 的 position 位置与 directMemoryCacheAlignment 对齐
            final ByteBuffer memory = PlatformDependent.alignDirectBuffer(base, sizeClass.directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, base, memory, pageSize,
                    pageShifts, chunkSize, maxPageIdx);
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (sizeClass.directMemoryCacheAlignment == 0) {
                ByteBuffer memory = allocateDirect(capacity);
                return new PoolChunk<ByteBuffer>(this, memory, memory, capacity);
            }

            final ByteBuffer base = allocateDirect(capacity + sizeClass.directMemoryCacheAlignment);
            final ByteBuffer memory = PlatformDependent.alignDirectBuffer(base, sizeClass.directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, base, memory, capacity);
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner((ByteBuffer) chunk.base);
            } else {
                PlatformDependent.freeDirectBuffer((ByteBuffer) chunk.base);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, PooledByteBuf<ByteBuffer> dstBuf, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dstBuf.memory) + dstBuf.offset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                ByteBuffer dst = dstBuf.internalNioBuffer();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstBuf.offset);
                dst.put(src);
            }
        }
    }

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }

    @Override
    public int sizeIdx2size(int sizeIdx) {
        return sizeClass.sizeIdx2size(sizeIdx);
    }

    @Override
    public int sizeIdx2sizeCompute(int sizeIdx) {
        return sizeClass.sizeIdx2sizeCompute(sizeIdx);
    }

    @Override
    public long pageIdx2size(int pageIdx) {
        return sizeClass.pageIdx2size(pageIdx);
    }

    @Override
    public long pageIdx2sizeCompute(int pageIdx) {
        return sizeClass.pageIdx2sizeCompute(pageIdx);
    }

    @Override
    public int size2SizeIdx(int size) {
        return sizeClass.size2SizeIdx(size);
    }

    @Override
    public int pages2pageIdx(int pages) {
        return sizeClass.pages2pageIdx(pages);
    }

    @Override
    public int pages2pageIdxFloor(int pages) {
        return sizeClass.pages2pageIdxFloor(pages);
    }

    @Override
    public int normalizeSize(int size) {
        return sizeClass.normalizeSize(size);
    }
}
