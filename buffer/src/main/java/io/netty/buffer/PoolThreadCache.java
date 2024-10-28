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


import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.buffer.PoolArena.SizeClass;
import io.netty.util.Recycler.EnhancedHandle;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.ObjectPool.Handle;
import io.netty.util.internal.ObjectPool.ObjectCreator;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acts a Thread cache for allocations. This implementation is moduled after
 * <a href="https://people.freebsd.org/~jasone/jemalloc/bsdcan2006/jemalloc.pdf">jemalloc</a> and the descripted
 * technics of
 * <a href="https://www.facebook.com/notes/facebook-engineering/scalable-memory-allocation-using-jemalloc/480222803919">
 * Scalable memory allocation using jemalloc</a>.
 */
final class PoolThreadCache {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolThreadCache.class);
    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    final PoolArena<byte[]> heapArena;
    final PoolArena<ByteBuffer> directArena;

    // Hold the caches for the different size classes, which are small and normal.
    private final MemoryRegionCache<byte[]>[] smallSubPageHeapCaches;
    private final MemoryRegionCache<ByteBuffer>[] smallSubPageDirectCaches;
    private final MemoryRegionCache<byte[]>[] normalHeapCaches;
    private final MemoryRegionCache<ByteBuffer>[] normalDirectCaches;
    // allocations 的次数达到该阈值的时候，则将 PoolThreadCache 中的所有缓存 smallSubPageDirectCaches，normalDirectCaches 中剩余的内存块全部释放回 chunk 中
    // 并重置 allocations 为 0。
    // 这里的目的是为了控制缓存的容量，对于那些不经常被使用的内存块（allocations 已经达到阈值，仍然空闲），就需要尽早释放回 chunk 中不在缓存
    private final int freeSweepAllocationThreshold;//8192
    private final AtomicBoolean freed = new AtomicBoolean();
    @SuppressWarnings("unused") // Field is only here for the finalizer.
    // 当 PoolTheadCache 对应的 thread exit 的时候，通过 FreeOnFinalize 对象释放 PoolTheadCache 的资源
    // 这里只能用 FinalReference 不能用 jdk.internal.ref.Cleaner
    // 因为 Cleaner 执行的时候，PoolThreadCache 可能已经被回收了，而 FinalReference 可以复活 FreeOnFinalize 近而复活 PoolThreadCache，执行清理
    // Cleaner 的 thunk 里不能引用 PoolThreadCache，这样会导致 PoolThreadCache 无法回收（同 DirectByteBuffer , MappedByteBuffer）
    // java.lang.ref.Cleaner Java9 才支持
    private final FreeOnFinalize freeOnFinalize;
    // 从该 PoolThreadCache 中分配内存块的总体次数
    private int allocations;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolThreadCache(PoolArena<byte[]> heapArena, PoolArena<ByteBuffer> directArena,
                    int smallCacheSize, int normalCacheSize, int maxCachedBufferCapacity,
                    int freeSweepAllocationThreshold, boolean useFinalizer) {
        checkPositiveOrZero(maxCachedBufferCapacity, "maxCachedBufferCapacity");
        this.freeSweepAllocationThreshold = freeSweepAllocationThreshold;
        this.heapArena = heapArena;
        // 线程与 PoolArena 进行绑定
        this.directArena = directArena;
        if (directArena != null) {
            // smallCacheSize = DEFAULT_SMALL_CACHE_SIZE = 256 , 表示每一个内存规格对应的 MemoryRegionCache 可以缓存的内存块个数
            smallSubPageDirectCaches = createSubPageCaches(smallCacheSize, directArena.sizeClass.nSubpages);
            // DEFAULT_NORMAL_CACHE_SIZE = normalCacheSize = 64 , 每一个 normal 内存规格的 MemoryRegionCache 可以缓存的内存块个数
            normalDirectCaches = createNormalCaches(normalCacheSize, maxCachedBufferCapacity, directArena);
            // 线程绑定数加 1
            directArena.numThreadCaches.getAndIncrement();
        } else {
            // No directArea is configured so just null out all caches
            smallSubPageDirectCaches = null;
            normalDirectCaches = null;
        }
        if (heapArena != null) {
            // Create the caches for the heap allocations
            smallSubPageHeapCaches = createSubPageCaches(smallCacheSize, heapArena.sizeClass.nSubpages);
            normalHeapCaches = createNormalCaches(normalCacheSize, maxCachedBufferCapacity, heapArena);
            heapArena.numThreadCaches.getAndIncrement();
        } else {
            // No heapArea is configured so just null out all caches
            smallSubPageHeapCaches = null;
            normalHeapCaches = null;
        }

        // Only check if there are caches in use.
        if ((smallSubPageDirectCaches != null || normalDirectCaches != null
                || smallSubPageHeapCaches != null || normalHeapCaches != null)
                && freeSweepAllocationThreshold < 1) {
            throw new IllegalArgumentException("freeSweepAllocationThreshold: "
                    + freeSweepAllocationThreshold + " (expected: > 0)");
        }
        freeOnFinalize = useFinalizer ? new FreeOnFinalize(this) : null;
    }

    private static <T> MemoryRegionCache<T>[] createSubPageCaches(
            int cacheSize, int numCaches) {
        if (cacheSize > 0 && numCaches > 0) {
            // 按照每一个 small 内存规格创建 MemoryRegionCache 数组
            // cacheIndex 就是对应的 small 内存规格的 sizeIndex
            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[numCaches];
            for (int i = 0; i < cache.length; i++) {
                // TODO: maybe use cacheSize / cache.length
                cache[i] = new SubPageMemoryRegionCache<T>(cacheSize);
            }
            return cache;
        } else {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> MemoryRegionCache<T>[] createNormalCaches(
            int cacheSize, int maxCachedBufferCapacity, PoolArena<T> area) {
        if (cacheSize > 0 && maxCachedBufferCapacity > 0) {
            // maxCachedBufferCapacity = 32K，可缓存最大的内存规格，超过该规格则不缓存，直接释放回内存池
            int max = Math.min(area.sizeClass.chunkSize, maxCachedBufferCapacity);
            // Create as many normal caches as we support based on how many sizeIdx we have and what the upper
            // bound is that we want to cache in general.
            // 为每一个 normal 规格的内存块建立 MemoryRegionCache 缓存
            List<MemoryRegionCache<T>> cache = new ArrayList<MemoryRegionCache<T>>() ;
            for (int idx = area.sizeClass.nSubpages; idx < area.sizeClass.nSizes &&
                    area.sizeClass.sizeIdx2size(idx) <= max; idx++) {
                // normal 内存规格只能缓存 32K 的内存块，超过 32K 不进行缓存，直接释放回内存池
                // cacheSize = 64 , 表示可以缓存多少个该规格的内存块
                cache.add(new NormalMemoryRegionCache<T>(cacheSize));
            }
            // 指定数组的运行时类型为 MemoryRegionCache[]
            // 如果原 list 中的元素和指定类型 MemoryRegionCache 相同则直接返回
            // 如果不相同，则重新创建一个同等大小的 MemoryRegionCache[] 数组返回
            return cache.toArray(new MemoryRegionCache[0]);
        } else {
            return null;
        }
    }

    // val > 0
    static int log2(int val) {
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateSmall(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int sizeIdx) {
        return allocate(cacheForSmall(area, sizeIdx), buf, reqCapacity);
    }

    /**
     * Try to allocate a normal buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateNormal(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int sizeIdx) {
        return allocate(cacheForNormal(area, sizeIdx), buf, reqCapacity);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private boolean allocate(MemoryRegionCache<?> cache, PooledByteBuf buf, int reqCapacity) {
        if (cache == null) {
            // no cache found so just return false here
            return false;
        }
        // true 表示分配成功，false 表示分配失败（缓存没有了）
        boolean allocated = cache.allocate(buf, reqCapacity, this);
        if (++ allocations >= freeSweepAllocationThreshold) {
            allocations = 0;
            // 如果 PoolThreadCache 的分配次数 allocations 超过了阈值 freeSweepAllocationThreshold
            // 则将 PoolTheadCache 中缓存的所有空闲内存块都释放回 chunk 中。包含所有 subPage 的缓存和 normal 的缓存
            // 表示的语义是，分配到达了 freeSweepAllocationThreshold 阈值，还没有分配出去的内存块（仍然空闲的内存块）就是不经常使用的了
            // 对于不经常使用的内存块就应该释放回 chunk 中，不在缓存
            trim();
        }
        return allocated;
    }

    /**
     * Add {@link PoolChunk} and {@code handle} to the cache if there is enough room.
     * Returns {@code true} if it fit into the cache {@code false} otherwise.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    boolean add(PoolArena<?> area, PoolChunk chunk, ByteBuffer nioBuffer,
                long handle, int normCapacity, SizeClass sizeClass) {
        // 获取要释放内存 normCapacity 对应的内存规格 index
        int sizeIdx = area.sizeClass.size2SizeIdx(normCapacity);
        // 获取 sizeIdx 对应内存规格的 MemoryRegionCache
        MemoryRegionCache<?> cache = cache(area, sizeIdx, sizeClass);
        if (cache == null) {
            return false;
        }
        // 如果该 PoolThreadCache 已经被释放（对应的线程退出）
        if (freed.get()) {
            return false;
        }
        return cache.add(chunk, nioBuffer, handle, normCapacity);
    }

    private MemoryRegionCache<?> cache(PoolArena<?> area, int sizeIdx, SizeClass sizeClass) {
        switch (sizeClass) {
        case Normal:
            return cacheForNormal(area, sizeIdx);
        case Small:
            return cacheForSmall(area, sizeIdx);
        default:
            throw new Error();
        }
    }

    /**
     *  Should be called if the Thread that uses this cache is about to exist to release resources out of the cache
     */
    void free(boolean finalizer) {
        // As free() may be called either by the finalizer or by FastThreadLocal.onRemoval(...) we need to ensure
        // we only call this one time.
        if (freed.compareAndSet(false, true)) {
            if (freeOnFinalize != null) {
                // Help GC: this can race with a finalizer thread, but will be null out regardless
                // 如果用户线程先执行 free 流程，那么尽早的断开 freeOnFinalize 与 PoolThreadCache 之间的引用
                // 这样可以使 PoolThreadCache 尽早地被回收，不会被后面的 Finalizer 复活
                freeOnFinalize.cache = null;
            }
            int numFreed = free(smallSubPageDirectCaches, finalizer) +
                           free(normalDirectCaches, finalizer) +
                           free(smallSubPageHeapCaches, finalizer) +
                           free(normalHeapCaches, finalizer);

            if (numFreed > 0 && logger.isDebugEnabled()) {
                logger.debug("Freed {} thread-local buffer(s) from thread: {}", numFreed,
                             Thread.currentThread().getName());
            }

            if (directArena != null) {
                directArena.numThreadCaches.getAndDecrement();
            }

            if (heapArena != null) {
                heapArena.numThreadCaches.getAndDecrement();
            }
        }
    }

    private static int free(MemoryRegionCache<?>[] caches, boolean finalizer) {
        if (caches == null) {
            return 0;
        }

        int numFreed = 0;
        for (MemoryRegionCache<?> c: caches) {
            numFreed += free(c, finalizer);
        }
        return numFreed;
    }

    private static int free(MemoryRegionCache<?> cache, boolean finalizer) {
        if (cache == null) {
            return 0;
        }
        return cache.free(finalizer);
    }

    void trim() {
        trim(smallSubPageDirectCaches);
        trim(normalDirectCaches);
        trim(smallSubPageHeapCaches);
        trim(normalHeapCaches);
    }

    private static void trim(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return;
        }
        for (MemoryRegionCache<?> c: caches) {
            trim(c);
        }
    }

    private static void trim(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return;
        }
        cache.trim();
    }

    private MemoryRegionCache<?> cacheForSmall(PoolArena<?> area, int sizeIdx) {
        if (area.isDirect()) {
            return cache(smallSubPageDirectCaches, sizeIdx);
        }
        return cache(smallSubPageHeapCaches, sizeIdx);
    }

    private MemoryRegionCache<?> cacheForNormal(PoolArena<?> area, int sizeIdx) {
        // We need to subtract area.sizeClass.nSubpages as sizeIdx is the overall index for all sizes.
        int idx = sizeIdx - area.sizeClass.nSubpages;
        if (area.isDirect()) {
            return cache(normalDirectCaches, idx);
        }
        return cache(normalHeapCaches, idx);
    }

    private static <T> MemoryRegionCache<T> cache(MemoryRegionCache<T>[] cache, int sizeIdx) {
        if (cache == null || sizeIdx > cache.length - 1) {
            return null;
        }
        return cache[sizeIdx];
    }

    /**
     * Cache used for buffers which are backed by TINY or SMALL size.
     */
    private static final class SubPageMemoryRegionCache<T> extends MemoryRegionCache<T> {
        SubPageMemoryRegionCache(int size) {
            super(size, SizeClass.Small);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity,
                PoolThreadCache threadCache) {
            chunk.initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    /**
     * Cache used for buffers which are backed by NORMAL size.
     */
    private static final class NormalMemoryRegionCache<T> extends MemoryRegionCache<T> {
        NormalMemoryRegionCache(int size) {
            super(size, SizeClass.Normal);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity,
                PoolThreadCache threadCache) {
            chunk.initBuf(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    private abstract static class MemoryRegionCache<T> {
        private final int size;
        private final Queue<Entry<T>> queue;
        private final SizeClass sizeClass;
        // 从该缓存中分配内存块的次数
        private int allocations;

        MemoryRegionCache(int size, SizeClass sizeClass) {
            // DEFAULT_SMALL_CACHE_SIZE 默认 256
            // 表示每一个 MemoryRegionCache 中，queue 中可以缓存的内存块个数
            this.size = MathUtil.safeFindNextPositivePowerOfTwo(size);
            queue = PlatformDependent.newFixedMpscQueue(this.size);
            this.sizeClass = sizeClass;
        }

        /**
         * Init the {@link PooledByteBuf} using the provided chunk and handle with the capacity restrictions.
         */
        protected abstract void initBuf(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle,
                                        PooledByteBuf<T> buf, int reqCapacity, PoolThreadCache threadCache);

        /**
         * Add to cache if not already full.
         */
        @SuppressWarnings("unchecked")
        public final boolean add(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity) {
            Entry<T> entry = newEntry(chunk, nioBuffer, handle, normCapacity);
            boolean queued = queue.offer(entry);
            if (!queued) {
                // If it was not possible to cache the chunk, immediately recycle the entry
                entry.unguardedRecycle();
            }
            // true 表示缓存成功
            // false 表示缓存满了，添加失败
            return queued;
        }

        /**
         * Allocate something out of the cache if possible and remove the entry from the cache.
         */
        public final boolean allocate(PooledByteBuf<T> buf, int reqCapacity, PoolThreadCache threadCache) {
            // 从缓存中获取内存块
            Entry<T> entry = queue.poll();
            if (entry == null) {
                return false;
            }
            initBuf(entry.chunk, entry.nioBuffer, entry.handle, buf, reqCapacity, threadCache);
            entry.unguardedRecycle();

            // allocations is not thread-safe which is fine as this is only called from the same thread all time.
            // 从该 MemoryRegionCache 分配内存块的总体次数
            ++ allocations;
            return true;
        }

        /**
         * Clear out this cache and free up all previous cached {@link PoolChunk}s and {@code handle}s.
         */
        public final int free(boolean finalizer) {
            return free(Integer.MAX_VALUE, finalizer);
        }

        private int free(int max, boolean finalizer) {
            int numFreed = 0;
            for (; numFreed < max; numFreed++) {
                Entry<T> entry = queue.poll();
                if (entry != null) {
                    freeEntry(entry, finalizer);
                } else {
                    // all cleared
                    return numFreed;
                }
            }
            return numFreed;
        }

        /**
         * Free up cached {@link PoolChunk}s if not allocated frequently enough.
         */
        public final void trim() {
            // size 表示该 MemoryRegionCache 中可以缓存的内存块个数
            // allocations 表示分配出去的内存块个数
            // free 表示剩余多少内存块
            int free = size - allocations;
            allocations = 0;

            // We not even allocated all the number that are
            // 将剩余的内存块全部释放回 chunk 中
            if (free > 0) {
                free(free, false);
            }
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private  void freeEntry(Entry entry, boolean finalizer) {
            // Capture entry state before we recycle the entry object.
            PoolChunk chunk = entry.chunk;
            long handle = entry.handle;
            ByteBuffer nioBuffer = entry.nioBuffer;
            int normCapacity = entry.normCapacity;

            if (!finalizer) {
                // recycle now so PoolChunk can be GC'ed. This will only be done if this is not freed because of
                // a finalizer.
                // 如果是 finalizer，那么此时线程肯定是要退出了，那么后续也会回收对象池，或者对象池已经被回收了
                // 这里就没必要再执行 entry 的回收
                entry.recycle();
            }

            chunk.arena.freeChunk(chunk, handle, normCapacity, sizeClass, nioBuffer, finalizer);
        }

        static final class Entry<T> {
            final EnhancedHandle<Entry<?>> recyclerHandle;
            PoolChunk<T> chunk;
            ByteBuffer nioBuffer;
            long handle = -1;
            int normCapacity;

            Entry(Handle<Entry<?>> recyclerHandle) {
                this.recyclerHandle = (EnhancedHandle<Entry<?>>) recyclerHandle;
            }

            void recycle() {
                chunk = null;
                nioBuffer = null;
                handle = -1;
                recyclerHandle.recycle(this);
            }

            void unguardedRecycle() {
                chunk = null;
                nioBuffer = null;
                handle = -1;
                recyclerHandle.unguardedRecycle(this);
            }
        }

        @SuppressWarnings("rawtypes")
        private static Entry newEntry(PoolChunk<?> chunk, ByteBuffer nioBuffer, long handle, int normCapacity) {
            Entry entry = RECYCLER.get();
            entry.chunk = chunk;
            entry.nioBuffer = nioBuffer;
            entry.handle = handle;
            entry.normCapacity = normCapacity;
            return entry;
        }

        @SuppressWarnings("rawtypes")
        private static final ObjectPool<Entry> RECYCLER = ObjectPool.newPool(new ObjectCreator<Entry>() {
            @SuppressWarnings("unchecked")
            @Override
            public Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        });
    }

    private static final class FreeOnFinalize {

        private volatile PoolThreadCache cache;

        private FreeOnFinalize(PoolThreadCache cache) {
            this.cache = cache;
        }

        /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
        @SuppressWarnings({"FinalizeDeclaration", "deprecation"})
        @Override
        protected void finalize() throws Throwable {
            try {
                super.finalize();
            } finally {
                PoolThreadCache cache = this.cache;
                // this can race with a non-finalizer thread calling free: regardless who wins, the cache will be
                // null out
                this.cache = null;
                // cache 这里为 null 的情况是，用户线程先一步执行 FastThreadLocal.removeAll()
                // see io.netty.buffer.PoolThreadCache.free(boolean)
                // 尽早的断开了那么尽早的断开 freeOnFinalize 与 PoolThreadCache 之间的引用
                // 这样可以使 PoolThreadCache 尽早地被回收，不会被后面的 Finalizer 复活
                // 当后面 Finalizer 线程执行到这里的时候 cache 已经为 null 了，直接返回。
                if (cache != null) {
                    cache.free(true);
                }
            }
        }
    }
}
