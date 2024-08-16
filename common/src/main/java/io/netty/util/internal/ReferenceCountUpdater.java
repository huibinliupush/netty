/*
 * Copyright 2019 The Netty Project
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
package io.netty.util.internal;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCounted;

/**
 * Common logic for {@link ReferenceCounted} implementations
 */
public abstract class ReferenceCountUpdater<T extends ReferenceCounted> {
    /*
     * Implementation notes:
     *
     * For the updated int field:
     *   Even => "real" refcount is (refCnt >>> 1)
     *   Odd  => "real" refcount is 0
     *
     * (x & y) appears to be surprisingly expensive relative to (x == y). Thus this class uses
     * a fast-path in some places for most common low values when checking for live (even) refcounts,
     * for example: if (rawCnt == 2 || rawCnt == 4 || (rawCnt & 1) == 0) { ...
     */

    protected ReferenceCountUpdater() { }

    public static long getUnsafeOffset(Class<? extends ReferenceCounted> clz, String fieldName) {
        try {
            if (PlatformDependent.hasUnsafe()) {
                return PlatformDependent.objectFieldOffset(clz.getDeclaredField(fieldName));
            }
        } catch (Throwable ignore) {
            // fall-back
        }
        return -1;
    }

    protected abstract AtomicIntegerFieldUpdater<T> updater();

    protected abstract long unsafeOffset();
    // 4.1.17.Final 版本中用 getAndAdd 方法来替换 compareAndSet 方法。https://github.com/netty/netty/commit/83a19d565064ee36998eb94f946e5a4264001065
    // https://github.com/netty/netty/issues/8563 （4.1.32.Final 优化）
    // https://github.com/netty/netty/pull/8583

    // 4.1.35.Final 中将引用计数的逻辑集中封装在 ReferenceCountUpdater 中
    // https://github.com/netty/netty/pull/8614
    public final int initialValue() {
        return 2;
    }

    private static int realRefCnt(int rawCnt) {
        // 常见的引用计数 2 ， 4 的判断这里采用性能更高的 == (fast path)
        // 后面的使用 & 操作判断 rawCnt 是否为偶数，如果是偶数那么 realRefCnt = rawCnt >>> 1
        // 如果是奇数，realRefCnt = 0
        return rawCnt != 2 && rawCnt != 4 && (rawCnt & 1) != 0 ? 0 : rawCnt >>> 1;
    }

    /**
     * Like {@link #realRefCnt(int)} but throws if refCnt == 0
     * 如果该 ByteBuf 是 live 的也就是引用计数大于 0 ，该方法返回 RealRefCnt
     * 如果该 ByteBuf 不在 live 也就是引用计数等于 0 ， 就抛异常
     */
    private static int toLiveRealRefCnt(int rawCnt, int decrement) {
        if (rawCnt == 2 || rawCnt == 4 || (rawCnt & 1) == 0) {
            // 如果是偶数，表示引用计数大于 0 ， 该 ByteBuf 还在被引用，live
            return rawCnt >>> 1;
        }
        // odd rawCnt => already deallocated
        // 如果是奇数，则抛异常，奇数表示引用计数为 0 ，不在 live
        throw new IllegalReferenceCountException(0, -decrement);
    }

    private int nonVolatileRawCnt(T instance) {
        // TODO: Once we compile against later versions of Java we can replace the Unsafe usage here by varhandles.
        // REFCNT_FIELD_OFFSET
        final long offset = unsafeOffset();
        // offset = -1 表示 has no unSafe ， unsafe 读取是采用 nonVolatile 方式，updater 采用 Volatile 方式
        return offset != -1 ? PlatformDependent.getInt(instance, offset) : updater().get(instance);
    }

    public final int refCnt(T instance) {
        // 将 ByteBuf 中的 rawCnt 转换为 realRefCnt
        return realRefCnt(updater().get(instance));
    }

    public final boolean isLiveNonVolatile(T instance) {
        final long offset = unsafeOffset();
        final int rawCnt = offset != -1 ? PlatformDependent.getInt(instance, offset) : updater().get(instance);

        // The "real" ref count is > 0 if the rawCnt is even.
        // 如果 rawCnt 是偶数，那么表示 realRefCnt 大于 0 则返回 true
        // 如果 rawCnt 是偶数, 则表示 realRefCnt 等于 0，返回 false
        return rawCnt == 2 || rawCnt == 4 || rawCnt == 6 || rawCnt == 8 || (rawCnt & 1) == 0;
    }

    /**
     * An unsafe operation that sets the reference count directly
     */
    public final void setRefCnt(T instance, int refCnt) {
        // 每次更新 ByteBuf 中的 rawCnt 都是两倍增加
        // 如果 refCnt <= 0 ,也就是我们想清空引用计数，底层的 rawCnt 直接被设置为 1 表示不在 live
        updater().set(instance, refCnt > 0 ? refCnt << 1 : 1); // overflow OK here
    }

    /**
     * Resets the reference count to 1
     */
    public final void resetRefCnt(T instance) {
        updater().set(instance, initialValue());
    }

    public final T retain(T instance) {
        return retain0(instance, 1, 2);
    }

    public final T retain(T instance, int increment) {
        // all changes to the raw count are 2x the "real" change - overflow is OK
        int rawIncrement = checkPositive(increment, "increment") << 1;
        return retain0(instance, increment, rawIncrement);
    }

    // rawIncrement == increment << 1
    // increment 表示真实引用计数的语义 realRefCnt 需要增加的大小
    // rawIncrement 表示 ByteBuf 底层的 rawCnt 要增加的大小，是 increment 的两倍
    private T retain0(T instance, final int increment, final int rawIncrement) {
        // x86 架构下使用 getAndAdd（XADD指令） 性能会比 CompareAndSet (CMPXCHG指令)要好
        // https://dev.to/agutikov/who-is-faster-compareexchange-or-fetchadd-1pjc
        // https://medium.com/@pravvich/cas-and-faa-through-the-eyes-of-a-java-developer-8a028f213624
        // https://blog.csdn.net/cyuanxin/article/details/85652733
        // https://blogs.oracle.com/dave/atomic-fetch-and-add-vs-compare-and-swap
        // 本身是原子指令，比如 XCHG 和 XADD 汇编指令
        // 本身不是原子指令，但是被 LOCK 指令前缀修饰后成为原子指令，比如LOCK CMPXCHG

        // 将 ByteBuf 底层的 rawCnt 增加 rawIncrement
        // getAndAdd 乐观更新（先不考虑并发情况），CompareAndSet 悲观更新（考虑并发情况）
        // https://github.com/netty/netty/commit/83a19d565064ee36998eb94f946e5a4264001065
        int oldRef = updater().getAndAdd(instance, rawIncrement);
        // 如果 rawCnt 之前已经是奇数了，表示 ByteBuf 已经不在 live, 再次对 ByteBuf 进行 retain 要抛异常
        if (oldRef != 2 && oldRef != 4 && (oldRef & 1) != 0) {
            throw new IllegalReferenceCountException(0, increment);
        }
        // don't pass 0!
        if ((oldRef <= 0 && oldRef + rawIncrement >= 0)
                || (oldRef >= 0 && oldRef + rawIncrement < oldRef)) {
            // overflow case
            // rawCnt 字段溢出，则回退 (CompareAndSet 则不需要回退，所以 4.1.16.final 中的实现没有并发问题)
            // 而 getAndAdd 需要回退，且更新和回退不是原子操作，所以存在并发问题
            updater().getAndAdd(instance, -rawIncrement);
            throw new IllegalReferenceCountException(realRefCnt(oldRef), increment);
        }
        return instance;
    }

    public final boolean release(T instance) {
        // nonVolatile 的方式读取 ByteBuf 中的 refCnt
        int rawCnt = nonVolatileRawCnt(instance);
        // 如果 ByteBuf 只被引用了一次 （rawCnt == 2）则调用 tryFinalRelease0 ， 失败的话调用 retryRelease0 重试
        // 如果 ByteBuf 被引用了多次，那么就调用 nonFinalRelease0
        return rawCnt == 2 ? tryFinalRelease0(instance, 2) || retryRelease0(instance, 1)
                : nonFinalRelease0(instance, 1, rawCnt, toLiveRealRefCnt(rawCnt, 1));
    }

    public final boolean release(T instance, int decrement) {
        // release 的第一次读取采用 unSafe nonVolatile 的方式读取
        int rawCnt = nonVolatileRawCnt(instance);
        // 如果引用计数已经为 0 ，则抛出异常
        int realCnt = toLiveRealRefCnt(rawCnt, checkPositive(decrement, "decrement"));
        return decrement == realCnt ? tryFinalRelease0(instance, rawCnt) || retryRelease0(instance, decrement)
                : nonFinalRelease0(instance, decrement, rawCnt, realCnt);
    }

    private boolean tryFinalRelease0(T instance, int expectRawCnt) {
        // 将 ByteBuf 中的 refCnt 更新为 1 ，表示引用计数已经为 0
        // 如果失败则调用 retryRelease0 重试
        return updater().compareAndSet(instance, expectRawCnt, 1); // any odd number will work
    }

    private boolean nonFinalRelease0(T instance, int decrement, int rawCnt, int realCnt) {
        if (decrement < realCnt
                // all changes to the raw count are 2x the "real" change - overflow is OK
                && updater().compareAndSet(instance, rawCnt, rawCnt - (decrement << 1))) {
            // ByteBuf 的 rawCnt 减少 2 * decrement
            return false;
        }
        // CAS  失败则一直重试，如果引用计数已经为 0 ，那么抛出异常，不能再次 release
        return retryRelease0(instance, decrement);
    }

    private boolean retryRelease0(T instance, int decrement) {
        // 必须采用悲观的 compareAndSet ，而不能采用乐观的 getAndAdd
        // 因为 getAndAdd 的更新 rawCnt 以及回滚 rawCnt 不是原子的，容易导致并发问题，在并发情况下 rawCnt 容易处于不一致的状态。
        // 而 compareAndSet 是悲观的，更新以及回滚 rawCnt 是原子的，更容易识别出并发问题，有并发问题，CAS 就失败了，其他线程修改了，compareAndSet 就失败了，更能保证语义的正确性
        // 而 getAndAdd 在有并发问题的时候，仍然能成功，属于无脑原子性的增加，并不管此时有没有并发修改的问题，其他线程修改了，getAndAdd 在修改的基础上增加
        // 后续需要人工判断并发问题，回滚并发问题的操作与 getAndAdd 就不是原子的了，并且返回的这个 oldValue 已经是被其他线程修改了的，基于 oldValue 来保证语义是错误的
        // 这里需要确保引用计数为 0 的时候，release 要抛出异常
        for (;;) {
            // Volatile 读取
            int rawCnt = updater().get(instance), realCnt = toLiveRealRefCnt(rawCnt, decrement);
            if (decrement == realCnt) {
                // 如果引用计数为 0 ，那么将 refCnt 设置为 1
                // 因为 retain 用的 getAndAdd ，所以 release 就需要用 CAS
                // 因为在 release 的过程中，引用计数可能被 getAndAdd 修改，CAS 可以判断出这种情况
                // 如果 release 也用 getAndAdd 的话，tryFinalRelease0 就直接将引用计数设置为 1 了
                // 线程 1 执行 release 看到了 decrement == realCnt 条件进来，但线程2 同时执行 retain 进而先调用 getAndAdd
                // 其实现在 decrement ！= realCnt 了，但线程 1 还是执行了 tryFinalRelease0，因为是 CAS 实现 线程 1 就可以识别到
                // 进而在下一次 for 循环中进入 else if (decrement < realCnt) 分支。
                // 如果 release 是 getAndAdd 实现的话，这里就执行执行 tryFinalRelease0 了不会失败，直接将引用计数设置为 1，ByteBuf 被释放
                // 但其实现在 ByteBuf 的真实引用计数是不为 0 的，不应该被释放。
                if (tryFinalRelease0(instance, rawCnt)) {
                    return true;
                }
            } else if (decrement < realCnt) {
                // all changes to the raw count are 2x the "real" change
                // 如果引用计数不为 0 ，那么原来的 refCnt - 2 * decrement
                // 增加或者减少 refCnt 都是两倍的来 2x the "real" change
                if (updater().compareAndSet(instance, rawCnt, rawCnt - (decrement << 1))) {
                    return false;
                }
            } else {
                // rawCnt 向下溢出，抛出异常
                throw new IllegalReferenceCountException(realCnt, -decrement);
            }
            // CAS 失败之后调用 yield
            // 减少无畏的竞争，否则所有线程在高并发情况下都在这里 CAS 失败
            Thread.yield(); // this benefits throughput under high contention
        }
    }
}
