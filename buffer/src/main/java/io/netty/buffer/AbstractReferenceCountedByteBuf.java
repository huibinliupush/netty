/*
 * Copyright 2013 The Netty Project
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

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.util.internal.ReferenceCountUpdater;

/**
 * Abstract base class for {@link ByteBuf} implementations that count references.
 */
public abstract class AbstractReferenceCountedByteBuf extends AbstractByteBuf {

//    汇编指令被LOCK指令前缀修饰后，一起提供内存屏障效果，因为LOCK指令前缀带来如下效果：

//    将所有写缓冲区中的数据刷新到内存，并将所有涉及到的Cache Line状态置为无效，使得下次只能从内存读取
//    禁止指令重排序，即“B之前的语句集A禁止被重排序到B之后，B之后的语句集C禁止被重排序到B之前，执行顺序必为A，B，C”，需要注意的是，上述叙述中的语句集A和C内部是可能存在重排序的

    // unsafe 读取是采用 nonVolatile 方式，不使用 Lock 前缀指令访存 ，读取 cache line 没有内存屏障
    // 在 unsafe 访存的代码中不会加入内存屏障
    private static final long REFCNT_FIELD_OFFSET =
            ReferenceCountUpdater.getUnsafeOffset(AbstractReferenceCountedByteBuf.class, "refCnt");
    // AtomicIntegerFieldUpdater 采用 Volatile 方式读取（访存时使用 Lock 前缀指令），读取 cache line 但有内存屏障
    // 底层使用 CAS 具有 Volatile 的语义，因为前面都加了 Lock 前缀
    // Atomic* 访存的代码中会加入内存屏障
    private static final AtomicIntegerFieldUpdater<AbstractReferenceCountedByteBuf> AIF_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractReferenceCountedByteBuf.class, "refCnt");

    private static final ReferenceCountUpdater<AbstractReferenceCountedByteBuf> updater =
            new ReferenceCountUpdater<AbstractReferenceCountedByteBuf>() {
        @Override
        protected AtomicIntegerFieldUpdater<AbstractReferenceCountedByteBuf> updater() {
            return AIF_UPDATER;
        }
        @Override
        protected long unsafeOffset() {
            return REFCNT_FIELD_OFFSET;
        }
    };

    // Value might not equal "real" reference count, all access should be via the updater
    // 初始引用计数为 2
    // 对一个 no-volatile 字段使用 CAS 操作本来有具有 volatile (LOCK addl)的语义，因为都使用了 LocK 前缀 —— LOCK CMPXCHG
    // 但对于 no-volatile 字段普通读写就没有 volatile 的语义了
    // 所以一个字段如果只有 CAS 操作（写），就不需要申明 volatile ， 但涉及到对这个字段的读取就需要申明 volatile
    // 但一般都有读写需求，写通过 CAS , 读的时候就需要申明字段为 volatile
    @SuppressWarnings("unused")
    private volatile int refCnt = updater.initialValue();

    protected AbstractReferenceCountedByteBuf(int maxCapacity) {
        super(maxCapacity);
    }

    @Override
    boolean isAccessible() {
        // Try to do non-volatile read for performance as the ensureAccessible() is racy anyway and only provide
        // a best-effort guard.
        return updater.isLiveNonVolatile(this);
    }

    @Override
    public int refCnt() {
        return updater.refCnt(this);
    }

    /**
     * An unsafe operation intended for use by a subclass that sets the reference count of the buffer directly
     */
    protected final void setRefCnt(int refCnt) {
        updater.setRefCnt(this, refCnt);
    }

    /**
     * An unsafe operation intended for use by a subclass that resets the reference count of the buffer to 1
     */
    protected final void resetRefCnt() {
        updater.resetRefCnt(this);
    }

    @Override
    public ByteBuf retain() {
        return updater.retain(this);
    }

    @Override
    public ByteBuf retain(int increment) {
        return updater.retain(this, increment);
    }

    @Override
    public ByteBuf touch() {
        return this;
    }

    @Override
    public ByteBuf touch(Object hint) {
        return this;
    }

    @Override
    public boolean release() {
        return handleRelease(updater.release(this));
    }

    @Override
    public boolean release(int decrement) {
        return handleRelease(updater.release(this, decrement));
    }

    private boolean handleRelease(boolean result) {
        if (result) {
            deallocate();
        }
        return result;
    }

    /**
     * Called once {@link #refCnt()} is equals 0.
     */
    protected abstract void deallocate();
}
