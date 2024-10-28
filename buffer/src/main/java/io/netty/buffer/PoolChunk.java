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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > run   - a run is a collection of pages
 * > chunk - a chunk is a collection of runs
 * > in this code chunkSize = maxPages * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to {@link PoolArena#sizeClass#size2SizeIdx(int)} method.
 * This ensures that when we request for memory segments of size > pageSize the normalizedCapacity
 * equals the next nearest size in {@link SizeClasses}.
 *
 *
 *  A chunk has the following layout:
 *
 *     /-----------------\
 *     | run             |
 *     |                 |
 *     |                 |
 *     |-----------------|
 *     | run             |
 *     |                 |
 *     |-----------------|
 *     | unalloctated    |
 *     | (freed)         |
 *     |                 |
 *     |-----------------|
 *     | subpage         |
 *     |-----------------|
 *     | unallocated     |
 *     | (freed)         |
 *     | ...             |
 *     | ...             |
 *     | ...             |
 *     |                 |
 *     |                 |
 *     |                 |
 *     \-----------------/
 *
 *
 * handle:
 * -------
 * a handle is a long number, the bit layout of a run looks like:
 *
 * oooooooo ooooooos ssssssss ssssssue bbbbbbbb bbbbbbbb bbbbbbbb bbbbbbbb
 *
 * o: runOffset (page offset in the chunk), 15bit
 * s: size (number of pages) of this run, 15bit
 * u: isUsed?, 1bit
 * e: isSubpage?, 1bit
 * b: bitmapIdx of subpage, zero if it's not subpage, 32bit
 *
 * runsAvailMap:
 * ------
 * a map which manages all runs (used and not in used).
 * For each run, the first runOffset and last runOffset are stored in runsAvailMap.
 * key: runOffset
 * value: handle
 *
 * runsAvail:
 * ----------
 * an array of {@link PriorityQueue}.
 * Each queue manages same size of runs.
 * Runs are sorted by offset, so that we always allocate runs with smaller offset.
 *
 *
 * Algorithm:
 * ----------
 *
 *   As we allocate runs, we update values stored in runsAvailMap and runsAvail so that the property is maintained.
 *
 * Initialization -
 *  In the beginning we store the initial run which is the whole chunk.
 *  The initial run:
 *  runOffset = 0
 *  size = chunkSize
 *  isUsed = no
 *  isSubpage = no
 *  bitmapIdx = 0
 *
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) find the first avail run using in runsAvails according to size
 * 2) if pages of run is larger than request pages then split it, and save the tailing run
 *    for later using
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) find a not full subpage according to size.
 *    if it already exists just return, otherwise allocate a new PoolSubpage and call init()
 *    note that this subpage object is added to subpagesPool in the PoolArena when we init() it
 * 2) call subpage.allocate()
 *
 * Algorithm: [free(handle, length, nioBuffer)]
 * ----------
 * 1) if it is a subpage, return the slab back into this subpage
 * 2) if the subpage is not used or it is a run, then start free this run
 * 3) merge continuous avail runs
 * 4) save the merged run
 *
 */
final class PoolChunk<T> implements PoolChunkMetric {
    // run size
    private static final int SIZE_BIT_LENGTH = 15;
    private static final int INUSED_BIT_LENGTH = 1;
    // 是否是从 subPage 中分配出来的内存块
    private static final int SUBPAGE_BIT_LENGTH = 1;
    private static final int BITMAP_IDX_BIT_LENGTH = 32;

    static final int IS_SUBPAGE_SHIFT = BITMAP_IDX_BIT_LENGTH;
    static final int IS_USED_SHIFT = SUBPAGE_BIT_LENGTH + IS_SUBPAGE_SHIFT;
    static final int SIZE_SHIFT = INUSED_BIT_LENGTH + IS_USED_SHIFT;
    static final int RUN_OFFSET_SHIFT = SIZE_BIT_LENGTH + SIZE_SHIFT;

    final PoolArena<T> arena;
    // 因为后续这些内存都需要与 nio 交互，所以 Netty 中的任何内存模型底层都需要依赖 Nio ByteBuffer
    // 内存基址(ByteBuffer)
    final Object base;
    // base 按照 directMemoryCacheAlignment 对齐之后的内存地址(ByteBuffer)
    // 后续会将该 memory 传递给分配出去的 PooledByteBuffer
    final T memory;
    // 4M 的全部都是池化的 Chunk , 超过 4M 的内存规格就属于 Huge 采用非池化的 Chunk 包装
    final boolean unpooled;

    /**
     * store the first page and last page of each avail run
     */
    private final LongLongHashMap runsAvailMap;

    /**
     * manage all avail runs
     * chunk 中的伙伴算法
     */
    private final IntPriorityQueue[] runsAvail;

    private final ReentrantLock runsAvailLock;

    /**
     * manage all subpages in this chunk
     */
    private final PoolSubpage<T>[] subpages;

    /**
     * Accounting of pinned memory – memory that is currently in use by ByteBuf instances.
     */
    private final LongCounter pinnedBytes = PlatformDependent.newLongCounter();

    private final int pageSize;
    private final int pageShifts;
    private final int chunkSize;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC（多余的视图 ByteBuffer 实例）, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    // 这些缓存的 nioBuffer 全部都是一样的，都是通过 memory.duplicate() 获得的,相当于是全部的 chunk
    // 后续 PooledByteBuffer 通过相关的 index 来操作这段 nioBuffer
    private final Deque<ByteBuffer> cachedNioBuffers;
    // 当前 Chunk 中剩余的字节数
    int freeBytes;

    PoolChunkList<T> parent;
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    @SuppressWarnings("unchecked")
    PoolChunk(PoolArena<T> arena, Object base, T memory, int pageSize, int pageShifts, int chunkSize, int maxPageIdx) {
        // 只对 4M 的 Chunk 进行池化
        unpooled = false;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        // 4M
        freeBytes = chunkSize;
        // 创建 IntPriorityQueue 数组，Chunk 中的伙伴系统
        // runsAvail 中的内存规格参照 pageIdx2sizeTab
        runsAvail = newRunsAvailqueueArray(maxPageIdx);
        runsAvailLock = new ReentrantLock();
        // 保存 Chunk 中所有 run 的 firt runOffset（第一个 page 在 chunk 中的偏移） 和 last runOffset（最后一个 page 在 chunk 中的偏移）
        runsAvailMap = new LongLongHashMap(-1);
        // 保存由这个 Chunk 分配出去的 Subpage
        subpages = new PoolSubpage[chunkSize >> pageShifts];

        //insert initial run, offset = 0, pages = chunkSize / pageSize
        int pages = chunkSize >> pageShifts;
        // 初始run : runOffset = 0 (15 bit) , size = pages(15 bits) , isUsed = 0 (1bit) , isSubpage = 0 (1bit), bitmapIdx = 0 (32bits)
        // 初始 run 就是一整个 chunk (4M)
        long initHandle = (long) pages << SIZE_SHIFT;
        // 将初始 run 插入到 runsAvail 数组中
        insertAvailRun(0, pages, initHandle);
        // 缓存 ByteBuffer 实例
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, Object base, T memory, int size) {
        unpooled = true;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        pageSize = 0;
        pageShifts = 0;
        runsAvailMap = null;
        runsAvail = null;
        runsAvailLock = null;
        subpages = null;
        chunkSize = size;
        cachedNioBuffers = null;
    }

    private static IntPriorityQueue[] newRunsAvailqueueArray(int size) {
        IntPriorityQueue[] queueArray = new IntPriorityQueue[size];
        for (int i = 0; i < queueArray.length; i++) {
            queueArray[i] = new IntPriorityQueue();
        }
        return queueArray;
    }

    private void insertAvailRun(int runOffset, int pages, long handle) {
        // 这里和伙伴系统的逻辑一模一样了
        // pages 对应的 pageSize 在 pageIdx2sizeTab 中的 pageIndex
        // 如果 pageSize 恰好夹在两个内存规格之间，那么就取上一个规格的 pageIndex
        // runsAvail[pageIdxFloor] 需要保证至少可以满足 pageSize 的规格申请
        // pages = 512 , pageSize = 4M , pageIndex 恰好为 31
        int pageIdxFloor = arena.sizeClass.pages2pageIdxFloor(pages);
        // runsAvail 数组中的 index 和 pageIdx2sizeTab 中的 index 含义一样，均为 pageIndex
        // runsAvail 数组中每一项 IntPriorityQueue，均保存着该 pageIndex 对应的内存块
        // 这些内存块按照地址从低到高被组织在 IntPriorityQueue 中
        IntPriorityQueue queue = runsAvail[pageIdxFloor];
        assert isRun(handle);
        // 清除 handle 中的 BITMAP 信息，按照 runOffset 排序
        // Runs are sorted by offset, so that we always allocate runs with smaller offset
        queue.offer((int) (handle >> BITMAP_IDX_BIT_LENGTH));

        // insert first page of run
        // 向 runsAvailMap 插入 run 的 first page 在 chunk 中的偏移 (粒度为 page)
        insertAvailRun0(runOffset, handle);
        if (pages > 1) {
            // insert last page of run
            // 向 runsAvailMap 插入 run 的 last page 在 chunk 中的偏移
            insertAvailRun0(lastPage(runOffset, pages), handle);
        }
    }

    private void insertAvailRun0(int runOffset, long handle) {
        long pre = runsAvailMap.put(runOffset, handle);
        assert pre == -1;
    }

    private void removeAvailRun(long handle) {
        int pageIdxFloor = arena.sizeClass.pages2pageIdxFloor(runPages(handle));
        // 从 runsAvail 中移除 run
        runsAvail[pageIdxFloor].remove((int) (handle >> BITMAP_IDX_BIT_LENGTH));
        // 从 runsAvailMap 中移除该 run 的 firstPageOffset ，lastPageOffset
        removeAvailRun0(handle);
    }

    private void removeAvailRun0(long handle) {
        // 该 run 在 chunk 内的偏移（page 粒度）
        int runOffset = runOffset(handle);
        // 该 run 包含的 pages 个数
        int pages = runPages(handle);
        //remove first page of run
        // runsAvailMap 中保存了该 run 中第一个 page 的 offset 和最后一个 page 的 offset
        // key : pageOffset. value:handler
        // 这里将该 run 的 offset 信息删除
        runsAvailMap.remove(runOffset);
        if (pages > 1) {
            //remove last page of run
            runsAvailMap.remove(lastPage(runOffset, pages));
        }
    }

    private static int lastPage(int runOffset, int pages) {
        return runOffset + pages - 1;
    }

    private long getAvailRunByOffset(int runOffset) {
        return runsAvailMap.get(runOffset);
    }

    @Override
    public int usage() {
        final int freeBytes;
        if (this.unpooled) {
            freeBytes = this.freeBytes;
        } else {
            runsAvailLock.lock();
            try {
                freeBytes = this.freeBytes;
            } finally {
                runsAvailLock.unlock();
            }
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache cache) {
        final long handle;
        // 如果 area 中的 smallSubpagePools[sizeIdx] 为空，那么就会走到这里
        // 在 chunk 中分配，然后填充 smallSubpagePools
        if (sizeIdx <= arena.sizeClass.smallMaxSizeIdx) {
            final PoolSubpage<T> nextSub;
            // small
            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.smallSubpagePools[sizeIdx];
            head.lock();
            try {
                nextSub = head.next;
                if (nextSub != head) {
                    assert nextSub.doNotDestroy && nextSub.elemSize == arena.sizeClass.sizeIdx2size(sizeIdx) :
                            "doNotDestroy=" + nextSub.doNotDestroy + ", elemSize=" + nextSub.elemSize + ", sizeIdx=" +
                                    sizeIdx;
                    handle = nextSub.allocate();
                    assert handle >= 0;
                    assert isSubpage(handle);
                    nextSub.chunk.initBufWithSubpage(buf, null, handle, reqCapacity, cache);
                    return true;
                }
                handle = allocateSubpage(sizeIdx, head);
                if (handle < 0) {
                    return false;
                }
                assert isSubpage(handle);
            } finally {
                head.unlock();
            }
        } else {
            // normal
            // runSize must be multiple of pageSize
            int runSize = arena.sizeClass.sizeIdx2size(sizeIdx);
            // 从 chunk 中分配 runSize 大小的内存块
            handle = allocateRun(runSize);
            if (handle < 0) {
                // 分配失败
                return false;
            }
            // normal 规格的内存块不是 subPage
            assert !isSubpage(handle);
        }

        ByteBuffer nioBuffer = cachedNioBuffers != null? cachedNioBuffers.pollLast() : null;
        // nioBuffer 为 null 的话，后续访问 PooledByteBuffer 的时候通过 memory.duplicate() 获取
        // see : io.netty.buffer.PooledByteBuf.internalNioBuffer()
        initBuf(buf, nioBuffer, handle, reqCapacity, cache);
        return true;
    }

    private long allocateRun(int runSize) {
        // 计算 runSize 包含多少个 pages
        int pages = runSize >> pageShifts;
        // 如果 pages 对应的 pageSize 恰好夹在 pageIdx2sizeTab 中两个内存规格之间
        // 那么就取最大的内存规格对应的 pageIdx
        int pageIdx = arena.sizeClass.pages2pageIdx(pages);

        runsAvailLock.lock();
        try {
            //find first queue which has at least one big enough run
            // 按照伙伴算法，从 pageIdx 开始在 runsAvail 数组中查找第一个不为空的 IntPriorityQueue（对应尺寸内存块 run 的集合）
            int queueIdx = runFirstBestFit(pageIdx);
            // chunk 已经没有剩余内存了，返回 -1
            if (queueIdx == -1) {
                return -1;
            }

            //get run with min offset in this queue
            // 获取 queueIdx 对应内存规格的 IntPriorityQueue
            IntPriorityQueue queue = runsAvail[queueIdx];
            // 获取内存地址最低的一个 run, 内存尺寸为 pageIdx2sizeTab[queueIdx]
            long handle = queue.poll();
            assert handle != IntPriorityQueue.NO_VALUE;
            // runOffset(15bits) , size(15bits),isUsed(1bit),isSubPapge(1bit),bitmapIndex(32bits)
            handle <<= BITMAP_IDX_BIT_LENGTH;
            assert !isUsed(handle) : "invalid handle: " + handle;
            // 从 runsAvailMap 中删除该 run 的 offset 信息
            removeAvailRun0(handle);
            // 如果该 runSize 恰好和我们请求的 size 一致，那么就直接分配
            // 如果该 runSize 大于我们请求的 size , 就需要将剩余的内存块放入到对应的规格 runsAvail 中
            // 然后将请求 size 的内存块分配出去
            handle = splitLargeRun(handle, pages);
            // 此次分配出去的内存大小
            int pinnedSize = runSize(pageShifts, handle);
            freeBytes -= pinnedSize;
            return handle;
        } finally {
            runsAvailLock.unlock();
        }
    }

    /*
    * requestSize: 3072,runSize: 24576
      requestSize: 4096,runSize: 8192
      requestSize: 5120,runSize: 40960
      requestSize: 7168,runSize: 57344
      requestSize: 8192,runSize: 8192
      requestSize: 28672,runSize: 57344
    *
    * */
    private int calculateRunSize(int sizeIdx) {
        // 一个 page 最大可以容纳多少个内存块（Element）
        // pageSize / 16(最小内存块尺寸)
        int maxElements = 1 << pageShifts - SizeClasses.LOG2_QUANTUM;
        int runSize = 0;
        int nElements;
        // sizeIdx 对应的内存规格，slab 将会按照 elemSize 进行切分
        final int elemSize = arena.sizeClass.sizeIdx2size(sizeIdx);

        //find lowest common multiple of pageSize and elemSize
        // 查找 pageSize 与 elemSize 的最小公倍数
        do {
            runSize += pageSize;
            nElements = runSize / elemSize;
        } while (nElements < maxElements && runSize != nElements * elemSize);

        while (nElements > maxElements) {
            // runSize 太大了，缩减到 nElements = maxElements
            runSize -= pageSize;
            nElements = runSize / elemSize;
        }

        assert nElements > 0;
        assert runSize <= chunkSize;
        assert runSize >= elemSize;

        return runSize;
    }

    private int runFirstBestFit(int pageIdx) {
        // 如果 chunk 是一个全新的 ，那么直接从 runsAvail 最后一个规格（chunkSize）开始分配
        if (freeBytes == chunkSize) {
            return arena.sizeClass.nPSizes - 1;
        }
        // 按照伙伴查找算法，先从 pageIdx 规格开始查找对应的 IntPriorityQueue 是否有内存块（runs）
        // 如果没有就一直向后查找，直到找到一个不为空的 IntPriorityQueue
        for (int i = pageIdx; i < arena.sizeClass.nPSizes; i++) {
            IntPriorityQueue queue = runsAvail[i];
            if (queue != null && !queue.isEmpty()) {
                return i;
            }
        }
        // 如果 chunk 全部分配出去了，则返回 -1
        return -1;
    }

    private long splitLargeRun(long handle, int needPages) {
        assert needPages > 0;
        // 该 run 中包含的 pages 个数
        int totalPages = runPages(handle);
        assert needPages <= totalPages;
        // 多出来的 pages
        int remPages = totalPages - needPages;

        if (remPages > 0) {
            // 获取 run 在 chunk 中的 pageOffset
            int runOffset = runOffset(handle);

            // keep track of trailing unused pages for later use
            // 获取剩余内存块在 chunk 中的 pageOffset
            // [runOffset , availOffset - 1] 这段内存将会被分配出去
            int availOffset = runOffset + needPages;
            // 将剩余的内存块重新包装成 run
            long availRun = toRunHandle(availOffset, remPages, 0);
            // 将剩余的 run, 重新放回到伙伴系统 runsAvail 中
            // 注意这里并不会减半分裂，而是直接将 run 放回到 remPages 内存规格对应的 runsAvail 数组中
            insertAvailRun(availOffset, remPages, availRun);

            // not avail
            // 将 needPages 分配出去
            return toRunHandle(runOffset, needPages, 1);
        }

        //mark it as used
        // needPages = totalPages，说明正好，将整个 run 分配出去
        handle |= 1L << IS_USED_SHIFT;
        return handle;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity. Any PoolSubpage created / initialized here is added to
     * subpage pool in the PoolArena that owns this PoolChunk.
     *
     * @param sizeIdx sizeIdx of normalized size
     * @param head head of subpages
     *
     * @return index in memoryMap
     */
    private long allocateSubpage(int sizeIdx, PoolSubpage<T> head) {
        //allocate a new run
        // 计算需要分配多大的 Subpage，也就是一个 slab 应该分配多大
        // size 与 pageSize 的最小公倍数
        int runSize = calculateRunSize(sizeIdx);
        //runSize must be multiples of pageSize
        long runHandle = allocateRun(runSize);
        if (runHandle < 0) {
            return -1;
        }

        int runOffset = runOffset(runHandle);
        assert subpages[runOffset] == null;
        int elemSize = arena.sizeClass.sizeIdx2size(sizeIdx);

        PoolSubpage<T> subpage = new PoolSubpage<T>(head, this, pageShifts, runOffset,
                runSize(pageShifts, runHandle), elemSize);

        subpages[runOffset] = subpage;
        return subpage.allocate();
    }

    /**
     * Free a subpage or a run of pages When a subpage is freed from PoolSubpage, it might be added back to subpage pool
     * of the owning PoolArena. If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize,
     * we can completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, int normCapacity, ByteBuffer nioBuffer) {
        if (isSubpage(handle)) {
            int sIdx = runOffset(handle);
            PoolSubpage<T> subpage = subpages[sIdx];
            assert subpage != null;
            PoolSubpage<T> head = subpage.chunk.arena.smallSubpagePools[subpage.headIndex];
            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            head.lock();
            try {
                assert subpage.doNotDestroy;
                // true 表示 subpage 还是一个 partial subpage , 继续留在链表中分配内存块
                // false 表示 subpage 变成了一个 empty subpage ，从 smallSubpagePools 链表中移除
                if (subpage.free(head, bitmapIdx(handle))) {
                    //the subpage is still used, do not free it
                    return;
                }
                assert !subpage.doNotDestroy;
                // Null out slot in the array as it was freed and we should not use it anymore.
                // 移除 empty subpage,后续需要将 subpage 背后的 run 释放回 runsAvail 中
                subpages[sIdx] = null;
            } finally {
                head.unlock();
            }
        }
        // 内存块的释放：empty subpage 和 normal 内存块的释放均会走到这里
        // 获取内存块大小
        int runSize = runSize(pageShifts, handle);
        //start free run
        runsAvailLock.lock();
        try {
            // collapse continuous runs, successfully collapsed runs
            // will be removed from runsAvail and runsAvailMap
            // 在 chunk 中向前，向后合并和 handle 内存连续的 run
            long finalRun = collapseRuns(handle);

            // set run as not used
            // USED 的 bit 位设置为 0
            finalRun &= ~(1L << IS_USED_SHIFT);
            // if it is a subpage, set it to run
            // SUBPAGE 的 bit 位设置为 0
            finalRun &= ~(1L << IS_SUBPAGE_SHIFT);
            // 将合并后的 run 插入到伙伴系统重
            insertAvailRun(runOffset(finalRun), runPages(finalRun), finalRun);
            // 更新 chunk 的空闲内存技术
            freeBytes += runSize;
        } finally {
            runsAvailLock.unlock();
        }

        if (nioBuffer != null && cachedNioBuffers != null &&
            cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            // 默认可以缓存 1023 个 nioBuffer（全部都是 chunk 的 duplicates 视图）
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    private long collapseRuns(long handle) {
        // 在 chunk 中不断的向前合并内存连续的 run
        // 然后在不断的向后合并内存连续的 run
        return collapseNext(collapsePast(handle));
    }

    private long collapsePast(long handle) {
        // 不断的向前合并内存连续的 run
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);
            // 查看该 run 的前面是否有连续的 run
            // 到 runsAvailMap 中查询前面是否有内存连续的 run
            // 如果有 pastRun 中的 lastPageOffset 就是 runOffset - 1
            long pastRun = getAvailRunByOffset(runOffset - 1);
            if (pastRun == -1) {
                // handle 前面不存在连续的 run
                return handle;
            }

            int pastOffset = runOffset(pastRun);
            int pastPages = runPages(pastRun);

            //is continuous
            if (pastRun != handle && pastOffset + pastPages == runOffset) {
                //remove past run
                removeAvailRun(pastRun);
                // 重新合并成一个更大的 run
                handle = toRunHandle(pastOffset, pastPages + runPages, 0);
            } else {
                return handle;
            }
        }
    }

    private long collapseNext(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);
            // 向后查找内存连续的 run, nextRun 的 firstPageOffset = runOffset + runPages
            long nextRun = getAvailRunByOffset(runOffset + runPages);
            if (nextRun == -1) {
                // 后面不存在内存连续的 run
                return handle;
            }

            int nextOffset = runOffset(nextRun);
            int nextPages = runPages(nextRun);

            //is continuous
            if (nextRun != handle && runOffset + runPages == nextOffset) {
                //remove next run
                removeAvailRun(nextRun);
                handle = toRunHandle(runOffset, runPages + nextPages, 0);
            } else {
                return handle;
            }
        }
    }

    private static long toRunHandle(int runOffset, int runPages, int inUsed) {
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) runPages << SIZE_SHIFT
               | (long) inUsed << IS_USED_SHIFT;
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                 PoolThreadCache threadCache) {
        if (isSubpage(handle)) {
            initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        } else {
            // run 内包含的字节数
            int maxLength = runSize(pageShifts, handle);
            buf.init(this, nioBuffer, handle, runOffset(handle) << pageShifts,
                    reqCapacity, maxLength, arena.parent.threadCache());
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                            PoolThreadCache threadCache) {
        int runOffset = runOffset(handle);
        int bitmapIdx = bitmapIdx(handle);

        PoolSubpage<T> s = subpages[runOffset];
        assert s.isDoNotDestroy();
        assert reqCapacity <= s.elemSize : reqCapacity + "<=" + s.elemSize;
        // 该内存块在 chunk 中的偏移（字节为单位）
        int offset = (runOffset << pageShifts) + bitmapIdx * s.elemSize;
        buf.init(this, nioBuffer, handle, offset, reqCapacity, s.elemSize, threadCache);
    }

    void incrementPinnedMemory(int delta) {
        assert delta > 0;
        pinnedBytes.add(delta);
    }

    void decrementPinnedMemory(int delta) {
        assert delta > 0;
        pinnedBytes.add(-delta);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        if (this.unpooled) {
            return freeBytes;
        }
        runsAvailLock.lock();
        try {
            return freeBytes;
        } finally {
            runsAvailLock.unlock();
        }
    }

    public int pinnedBytes() {
        return (int) pinnedBytes.value();
    }

    @Override
    public String toString() {
        final int freeBytes;
        if (this.unpooled) {
            freeBytes = this.freeBytes;
        } else {
            runsAvailLock.lock();
            try {
                freeBytes = this.freeBytes;
            } finally {
                runsAvailLock.unlock();
            }
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }

    static int runOffset(long handle) {
        return (int) (handle >> RUN_OFFSET_SHIFT);
    }

    static int runSize(int pageShifts, long handle) {
        return runPages(handle) << pageShifts;
    }

    static int runPages(long handle) {
        return (int) (handle >> SIZE_SHIFT & 0x7fff);
    }

    static boolean isUsed(long handle) {
        return (handle >> IS_USED_SHIFT & 1) == 1L;
    }

    static boolean isRun(long handle) {
        return !isSubpage(handle);
    }

    static boolean isSubpage(long handle) {
        return (handle >> IS_SUBPAGE_SHIFT & 1) == 1L;
    }

    static int bitmapIdx(long handle) {
        return (int) handle;
    }
}
