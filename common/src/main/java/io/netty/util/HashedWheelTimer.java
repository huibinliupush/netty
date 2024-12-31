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
package io.netty.util;

import static io.netty.util.internal.ObjectUtil.checkInRange;
import static io.netty.util.internal.ObjectUtil.checkPositive;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

import io.netty.util.concurrent.ImmediateExecutor;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.util.internal.StringUtil.simpleClassName;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 *
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 *
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 *
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * <h3>Implementation Details</h3>
 *
 * {@link HashedWheelTimer} is based on
 * <a href="https://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="https://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="https://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 */
public class HashedWheelTimer implements Timer {

    static final InternalLogger logger =
            InternalLoggerFactory.getInstance(HashedWheelTimer.class);
    // HashedWheelTimer 的实例个数
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    // HashedWheelTimer 实例超过 64 ，就会打印 error 警告日志
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();
    // 默认允许的 HashedWheelTimer 最大实例个数
    private static final int INSTANCE_COUNT_LIMIT = 64;
    // 1ms 对应的纳秒数，时间轮中 tickDuration 的最小值为 1ms，精度不能低于 1ms
    private static final long MILLISECOND_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    // 对 HashedWheelTimer 的所有实例进行全量采样，探测是否发生资源泄露（HashedWheelTimer 未释放）
    // 也是说 HashedWheelTimer 在使用完毕之后，未调用 stop 方法释放资源
    private static final ResourceLeakDetector<HashedWheelTimer> leakDetector = ResourceLeakDetectorFactory.instance()
            .newResourceLeakDetector(HashedWheelTimer.class, 1);

    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");
    // HashedWheelTimer 的资源泄露跟踪器
    // 默认为 true，无条件跟踪，false 并且 worker thread 不是守护线程（默认不是）才会跟踪
    private final ResourceLeakTracker<HashedWheelTimer> leak;
    // 封装 worker 线程要干的事情，这里是 HashedWheelTimer 的核心
    private final Worker worker = new Worker();
    // HashedWheelTimer 的 worker 线程，由它来驱动时间轮的转动，延时任务的执行
    private final Thread workerThread;
    // HashedWheelTimer 的状态，由 WORKER_STATE_UPDATER 负责更新
    public static final int WORKER_STATE_INIT = 0;
    public static final int WORKER_STATE_STARTED = 1;
    public static final int WORKER_STATE_SHUTDOWN = 2;
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    private volatile int workerState; // 0 - init, 1 - started, 2 - shut down
    // HashedWheelTimer 的精度，也就是时钟间隔，多久转动一次，默认 100ms, 最小值为 1ms
    private final long tickDuration;
    // HashedWheelTimer 核心数据结构，时间轮的数据结构是一个环形 hash table，默认 512 个 hash 槽
    private final HashedWheelBucket[] wheel;
    private final int mask;
    // 监听时间轮的启动事件
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);
    // 多线程在向 HashedWheelTimer 添加延时任务的时候，首先会将任务添加到 timeouts 中，而不是直接添加到时间轮里
    private final Queue<HashedWheelTimeout> timeouts = PlatformDependent.newMpscQueue();
    // 多线程取消的延时任务都会添加到 cancelledTimeouts
    private final Queue<HashedWheelTimeout> cancelledTimeouts = PlatformDependent.newMpscQueue();
    // HashedWheelTimer 当前待执行的延时任务个数
    private final AtomicLong pendingTimeouts = new AtomicLong(0);
    // HashedWheelTimer 中最大允许待执行的任务个数，超出限制，则拒接添加新的任务，默认为 -1 。表示不进行限制
    private final long maxPendingTimeouts;
    // 用于执行延时任务，之前的设计是只由 worker 线程单线程执行，现在支持多线程来执行延时任务, 这样可以让时间轮及时的处理延时任务
    // 4.1.69.Final SEE https://github.com/netty/netty/pull/11728
    // 用于应对大量的并发延时任务
    // 默认为 ImmediateExecutor , 由 worker 线程单线程执行
    private final Executor taskExecutor;
    // 时间轮的启动时间戳（纳秒）
    private volatile long startTime;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration the duration between tick
     * @param unit         the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, true);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @param leakDetection {@code true} if leak detection should be enabled always,
     *                      if false it will only be enabled if the worker thread is not
     *                      a daemon thread.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, leakDetection, -1);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory        a {@link ThreadFactory} that creates a
     *                             background {@link Thread} which is dedicated to
     *                             {@link TimerTask} execution.
     * @param tickDuration         the duration between tick
     * @param unit                 the time unit of the {@code tickDuration}
     * @param ticksPerWheel        the size of the wheel
     * @param leakDetection        {@code true} if leak detection should be enabled always,
     *                             if false it will only be enabled if the worker thread is not
     *                             a daemon thread.
     * @param  maxPendingTimeouts  The maximum number of pending timeouts after which call to
     *                             {@code newTimeout} will result in
     *                             {@link java.util.concurrent.RejectedExecutionException}
     *                             being thrown. No maximum pending timeouts limit is assumed if
     *                             this value is 0 or negative.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection,
            long maxPendingTimeouts) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, leakDetection,
                maxPendingTimeouts, ImmediateExecutor.INSTANCE);
    }
    /**
     * Creates a new timer.
     *
     * @param threadFactory        a {@link ThreadFactory} that creates a
     *                             background {@link Thread} which is dedicated to
     *                             {@link TimerTask} execution.
     * @param tickDuration         the duration between tick
     * @param unit                 the time unit of the {@code tickDuration}
     * @param ticksPerWheel        the size of the wheel
     * @param leakDetection        {@code true} if leak detection should be enabled always,
     *                             if false it will only be enabled if the worker thread is not
     *                             a daemon thread.
     * @param maxPendingTimeouts   The maximum number of pending timeouts after which call to
     *                             {@code newTimeout} will result in
     *                             {@link java.util.concurrent.RejectedExecutionException}
     *                             being thrown. No maximum pending timeouts limit is assumed if
     *                             this value is 0 or negative.
     * @param taskExecutor         The {@link Executor} that is used to execute the submitted {@link TimerTask}s.
     *                             The caller is responsible to shutdown the {@link Executor} once it is not needed
     *                             anymore.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection,
            long maxPendingTimeouts, Executor taskExecutor) {

        checkNotNull(threadFactory, "threadFactory");
        checkNotNull(unit, "unit");
        checkPositive(tickDuration, "tickDuration");
        checkPositive(ticksPerWheel, "ticksPerWheel");
        // 默认为 ImmediateExecutor ， 由调用线程直接执行（worker thread）
        // 执行是时间轮中的延时任务，用户可自定义 Executor，用于应对大量的延时任务，时间轮的 worker 执行不过来的情况
        // 保证时间轮及时执行延时任务，之前的设计只能由 worker 单线程执行（4.1.69.Final 之前）
        this.taskExecutor = checkNotNull(taskExecutor, "taskExecutor");

        // Normalize ticksPerWheel to power of two and initialize the wheel.
        wheel = createWheel(ticksPerWheel);
        mask = wheel.length - 1;

        // Convert tickDuration to nanos. 精度为纳秒
        long duration = unit.toNanos(tickDuration); // 默认 100ms

        // Prevent overflow. 防止时间轮的时钟周期溢出 —— duration * wheel.length
        if (duration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format(
                    "tickDuration: %d (expected: 0 < tickDuration in nanos < %d",
                    tickDuration, Long.MAX_VALUE / wheel.length));
        }
        // 时间轮精度 duration 不能小于 1ms
        if (duration < MILLISECOND_NANOS) {
            logger.warn("Configured tickDuration {} smaller than {}, using 1ms.",
                        tickDuration, MILLISECOND_NANOS);
            this.tickDuration = MILLISECOND_NANOS;
        } else {
            this.tickDuration = duration;
        }

        workerThread = threadFactory.newThread(worker);
        // leakDetection 为 true(默认) , 无条件进行资源泄露探测，时间轮的资源泄露就是不在使用的时候，忘记调用 stop 方法
        // leakDetection 为 false 并且 workerThread 不是守护线程，才会进行资源泄露探测
        // workerThread 默认不是守护线程，see java.util.concurrent.Executors.DefaultThreadFactory.newThread
        leak = leakDetection || !workerThread.isDaemon() ? leakDetector.track(this) : null;
        // 时间轮中，待执行延时任务的最大个数，超过限制则禁止添加新的任务
        this.maxPendingTimeouts = maxPendingTimeouts;

        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT &&
            WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            // 时间轮的实例个数超过 68 个，则打印 error 日志警告
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // This object is going to be GCed and it is assumed the ship has sailed to do a proper shutdown. If
            // we have not yet shutdown then we want to make sure we decrement the active instance count.

            // 确保 HashedWheelTimer 实例被 GC 的时候，它的 worker thread 可以被 shutdown（如果忘记调用 stop 的话）
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }

            // 当实例不是很多的时候，可以用 finalize 机制做清理资源，比如这里的时间轮，还有内存池中的 thread local cache
        }
    }

    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        // ticksPerWheel 必须是 2 的次幂，默认为 512
        ticksPerWheel = MathUtil.findNextPositivePowerOfTwo(ticksPerWheel);
        // 创建时间轮中的 hash 槽
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i ++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     *
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     */
    public void start() {
        switch (WORKER_STATE_UPDATER.get(this)) {
            case WORKER_STATE_INIT:
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    // 非守护线程
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // Wait until the startTime is initialized by the worker.
        // 等待 workerThread 的启动，workerThread 启动之后会设置 startTime，并执行 startTimeInitialized.countdown
        while (startTime == 0) {
            try {
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    @Override
    public Set<Timeout> stop() {
        // 在延时任务中不能执行停止时间轮的操作
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                            ".stop() cannot be called from " +
                            TimerTask.class.getSimpleName());
        }
        // 停止时间轮
        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            // cas 更新状态失败，这里时间轮的状态可能会是两种：
            // 1. WORKER_STATE_INIT 还未启动
            // 2. WORKER_STATE_SHUTDOWN 已经停止
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                // 如果时间轮还未启动，那么直接停止就好了
                INSTANCE_COUNTER.decrementAndGet();
                if (leak != null) {
                    boolean closed = leak.close(this);
                    assert closed;
                }
            }

            return Collections.emptySet();
        }

        try {
            boolean interrupted = false;
            while (workerThread.isAlive()) {
                // 中断 workerThread，使其从 sleep 中唤醒，执行时间轮关闭的逻辑
                // 如果 workerThread 在运行，那么此时时间轮已经是 WORKER_STATE_SHUTDOWN 状态，workerThread 会退出 do while 循环取执行时间轮的关闭逻辑
                workerThread.interrupt();
                try {
                    // 等待 workerThread 的结束
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    // 当前线程被中断
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            INSTANCE_COUNTER.decrementAndGet();
            if (leak != null) {
                // 关闭资源泄露探测
                boolean closed = leak.close(this);
                assert closed;
            }
        }
        // 获取还未来得及处理的延时任务
        Set<Timeout> unprocessed = worker.unprocessedTimeouts();
        Set<Timeout> cancelled = new HashSet<Timeout>(unprocessed.size());
        // 将还未来得及处理的任务全部取消，然后返回
        // https://github.com/netty/netty/pull/14083
        for (Timeout timeout : unprocessed) {
            if (timeout.cancel()) {
                cancelled.add(timeout);
            }
        }
        return cancelled;
    }

    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        checkNotNull(task, "task");
        checkNotNull(unit, "unit");
        // 待执行的延时任务计数 + 1
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();
        // maxPendingTimeouts 默认为 -1 。表示不对时间轮的延时任务个数进行限制
        // 如果达到限制，则不能继续向时间轮添加任务
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                + "timeouts (" + maxPendingTimeouts + ")");
        }
        // 懒启动时间论，worker 线程会等待 100ms 后执行
        start();

        // Add the timeout to the timeout queue which will be processed on the next tick.
        // During processing all the queued HashedWheelTimeouts will be added to the correct HashedWheelBucket.
        // 计算延时任务到期的时间戳
        // 时间戳的参考坐标系均以时间轮的启动时间 startTime 为起点
        // delay 是一个相对时间，只要我们选择的时间坐标系都是以 startTime 为基准就可以（后续添加任务也是如此，相同的时间坐标系，不影响 delay 的相对时间）
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        // Guard against overflow.
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        // 这里先是添加到 timeouts 中，而不是直接添加到时间轮中
        // 因为这里可能多线程执行，而时间轮是单线程的，为了保证多线程添加延时任务的线程安全性，所以要先添加到 mpsc queue 中
        timeouts.add(timeout);
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    private static void reportTooManyInstances() {
        if (logger.isErrorEnabled()) {
            String resourceType = simpleClassName(HashedWheelTimer.class);
            logger.error("You are creating too many " + resourceType + " instances. " +
                    resourceType + " is a shared resource that must be reused across the JVM, " +
                    "so that only a few instances are created.");
        }
    }

    private final class Worker implements Runnable {
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        private long tick;

        @Override
        public void run() {
            // Initialize the startTime.
            // nanoTime 只是一个时间戳，它并不能用来表示当前具体准确时间，但可以用来计算 elapsed time
            // currentTimeMillis 却可以用来表示当前具体准确时间
            startTime = System.nanoTime();
            if (startTime == 0) {
                // We use 0 as an indicator for the uninitialized value here, so make sure it's not 0 when initialized.
                startTime = 1;
            }

            // Notify the other threads waiting for the initialization at start().
            startTimeInitialized.countDown();

            do {
                // sleep 等待 tick + 1 的时间点，开始执行 tick 对应的 HashedWheelBucket
                // 当前 tick 对应的延时任务 deadline 范围为 [tick , tick + 1)
                // 比如当前 tick 对应的时间点是 100ms , 那么 tick 对应的 HashedWheelBucket 存放的延时任务 deadline 可能是 101ms , 110ms , 120ms 190ms 199ms ...
                // 如果时钟刚到达 tick 就开始执行，那么 110ms ，120ms，199ms 的延时任务就被提前执行了
                // 所以这里需要 sleep 到 tick + 1 的时间点也就是 200ms(绝对时间，不是相对时间)，才能执行 tick HashedWheelBucket 中的延时任务
                // 时间轮的精度由 tickDuration 决定，默认 100ms , 一般都会晚一点执行
                // 当延时时间恰好是 tickDuration 的倍数时，那么任务的执行往往会晚一个 tickDuration (不一定，取决于你什么时候添加延时任务)
                // 比如，当前 tick = 0 , 添加一个 100ms 后执行的任务，这个任务会被添加到 tick + 1 对应的 bucket 中
                // 1. waitForNextTick 会等到 tick + 1 的时间点去执行 tick 的 bucket 中的任务（空）
                // 2. waitForNextTick 会等到 tick + 2 的时间点去执行 tick + 1 的 bucket 中的任务，此时延时 100ms 的任务才会执行
                // 也就是在 tick = 0 的时间点添加，在 tick = 2 的时间点执行（执行 tick = 1 的任务），也就是会 200ms 后执行

                // 延时任务的误差到底有多大，取决于这个延时任务什么时候被添加到 timeouts 中
                // 如果在 tick = 0 这个时刻，你刚好添加一个延时任务(tick + 1)，那么就会晚 200ms 执行
                // 时间轮需要等到 tick + 1 这个时间点去执行 tick 的任务（消耗 100ms）,继续等到 tick + 2 这个时间点去执行 tick + 1 的任务（消耗 100ms）

                // 如果我们添加延时任务的时机，刚好卡到 tick + 1 (100ms 绝对时间)这个时间点往前那么一点点，也就是马上要到 tick + 1 但还没到这个时间点
                // 那么延时任务的执行时间就会比较准确，100ms 多一点点，比如 104ms 或者 106ms 这样子
                // 时间马上要到 tick + 1 了，那么当前时间轮的 tick 还是 tick ,当时间到 tick + 1 的时候， worker 线程会被唤醒执行 tick bucket 中的任务
                // 并在 transferTimeoutsToBuckets 中将延时任务添加到 tick + 1 对应的 bucket 中
                // 随后时间轮的 tick + 1 , 继续等到 tick + 2 的时候，来执行 tick + 1 中的延时任务 (消耗 100ms),这么这时这个延时任务就刚好延时 100 ms （多一点点忽略不计，可能 104ms）执行
                final long deadline = waitForNextTick();
                // deadline < 0 表示 currentTime 溢出，或者 workerThread 被中断
                if (deadline > 0) {
                    int idx = (int) (tick & mask);
                    // 将 cancelledTimeouts 中已经取消的 task 从对应 bucket 中删除
                    processCancelledTasks();
                    HashedWheelBucket bucket =
                            wheel[idx];
                    // 将 timeouts 中收集的延时任务添加到时间轮中
                    transferTimeoutsToBuckets();
                    // 执行当前 tick 对应 bucket 中的所有延时任务 deadline 范围为 (tick , tick + 1]
                    bucket.expireTimeouts(deadline);
                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            // Fill the unprocessedTimeouts so we can return them from stop() method.
            for (HashedWheelBucket bucket: wheel) {
                // 将 bucket 中还没来得及执行并且没有被取消的任务添加到 unprocessedTimeouts
                bucket.clearTimeouts(unprocessedTimeouts);
            }
            // 将 timeouts 中缓存的待执行任务（没有被取消）添加到 unprocessedTimeouts
            for (;;) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }
            // 将 cancelledTimeouts 中的延时任务从对应 bucket 中删除
            processCancelledTasks();
        }

        private void transferTimeoutsToBuckets() {
            // transfer only max. 100000 timeouts per tick to prevent a thread to stale the workerThread when it just
            // adds new timeouts in a loop.
            // 每个 tick 最多只能从 timeouts 中转移 10万个延时任务到时间轮中，防止极端情况下 worker 线程在这里不停地拉取任务，执行任务
            // 从而延误了新来的任务执行，等到下一个 tick 再来执行上一轮没有执行完或者拉取到的任务
            for (int i = 0; i < 100000; i++) {
                // 拉取待执行的延时任务
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    // Was cancelled in the meantime.
                    continue;
                }
                // 时间戳的参考坐标系均以时间轮的启动时间 startTime 为起点
                // 计算延时任务 timeout 转动多少个 tick (绝对值，从 tick = 0 开始计算)
                // 因为 timeout.deadline 是以 startTime 为起点的，所以这里的 calculated 也是从 tick = 0 开始计算的
                long calculated = timeout.deadline / tickDuration;
                // 从当前 tick 转动到 calculated 需要经过多少个时钟周期
                timeout.remainingRounds = (calculated - tick) / wheel.length;
                // calculated < 当前 tick , 则表示延时任务 timeout 已经过期了，那么就将过期的 timeout 放在当前 tick 中执行
                final long ticks = Math.max(calculated, tick); // Ensure we don't schedule for past.
                int stopIndex = (int) (ticks & mask);

                HashedWheelBucket bucket = wheel[stopIndex];
                bucket.addTimeout(timeout);
            }
        }

        private void processCancelledTasks() {
            for (;;) {
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * calculate goal nanoTime from startTime and current tick number,
         * then wait until that goal has been reached.
         * @return Long.MIN_VALUE if received a shutdown request,
         * current time otherwise (with Long.MIN_VALUE changed by +1)
         *
         * 时间戳的参考坐标系均以时间轮的启动时间 startTime 为起点
         */
        private long waitForNextTick() {
            // 比如当前 tick 为 0，那么对应的 bucket 存放的延时任务 deadline 范围为 [0 , 100ms)
            // 所以这里的 deadline 是 100ms , 时间轮需要 sleep 等到 100ms 的这个时间点才能执行 tick 中的 bucket
            long deadline = tickDuration * (tick + 1); // 该 deadline 时间戳是以时间轮的启动时间 startTime 为起点

            for (;;) {
                // 当前时间戳应该减去 startTime，时间戳的参考坐标系均以时间轮的启动时间 startTime 为起点
                // 比如当前时间戳 currentTime， deadline 时间戳
                final long currentTime = System.nanoTime() - startTime;
                // tickDuration 越小，时间轮的精度越高，同时 Worker 的繁忙程度也越高,如果 tickDuration 设置的过小，那么 worker 这里就会被频繁的唤醒
                // 这里需要保证 worker 至少要 sleep 1ms ，防止 worker 被频繁的唤醒，tickDuration 的最小值也是 1ms , 默认 100ms
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;
                // 如果 deadline 已过期，那么直接返回 currentTime，tick bucket 中延时任务的 deadline 小于等于 currentTime 的就会被执行
                if (sleepTimeMs <= 0) {
                    // currentTime 溢出
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }

                // Check if we run on windows, as if thats the case we will need
                // to round the sleepTime as workaround for a bug that only affect
                // the JVM if it runs on windows.
                //
                // See https://github.com/netty/netty/issues/356
                if (PlatformDependent.isWindows()) {
                    // 101ms - 109ms 这里需要设置为 100ms
                    // 111ms - 119ms sleepTimeMs 为 110ms
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                    if (sleepTimeMs == 0) {
                        sleepTimeMs = 1; // 0ms - 9ms  sleepTimeMs 为 1ms
                    }
                    // see https://www.javamex.com/tutorials/threads/sleep_issues.shtml
                    // sleepTimeMs 必须是 10 的倍数
                }

                try {
                    // sleep 等待到 deadline 时间点，然后执行当前 tick bucket 中的延时任务（timeout.deadline <= deadline）
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    // 时间轮被其他线程关闭，中断 worker 线程
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        public Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    private static final class HashedWheelTimeout implements Timeout, Runnable {

        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        private final HashedWheelTimer timer;
        private final TimerTask task;
        private final long deadline;

        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization" })
        private volatile int state = ST_INIT;

        // remainingRounds will be calculated and set by Worker.transferTimeoutsToBuckets() before the
        // HashedWheelTimeout will be added to the correct HashedWheelBucket.
        long remainingRounds;

        // This will be used to chain timeouts in HashedWheelTimerBucket via a double-linked-list.
        // As only the workerThread will act on it there is no need for synchronization / volatile.
        HashedWheelTimeout next;
        HashedWheelTimeout prev;

        // The bucket to which the timeout was added
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean cancel() {
            // only update the state it will be removed from HashedWheelBucket on next tick.
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            // If a task should be canceled we put this to another queue which will be processed on each tick.
            // So this means that we will have a GC latency of max. 1 tick duration which is good enough. This way
            // we can make again use of our MpscLinkedQueue and so minimize the locking / overhead as much as possible.
            timer.cancelledTimeouts.add(this);
            return true;
        }

        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        public int state() {
            return state;
        }

        @Override
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        public void expire() {
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                // 时间轮的 taskExecutor 负责执行延时任务
                timer.taskExecutor.execute(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown while submit " + TimerTask.class.getSimpleName()
                            + " for execution.", t);
                }
            }
        }

        @Override
        public void run() {
            try {
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;

            StringBuilder buf = new StringBuilder(192)
               .append(simpleClassName(this))
               .append('(')
               .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                   .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                   .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                      .append(task())
                      .append(')')
                      .toString();
        }
    }

    /**
     * Bucket that stores HashedWheelTimeouts. These are stored in a linked-list like datastructure to allow easy
     * removal of HashedWheelTimeouts in the middle. Also the HashedWheelTimeout act as nodes themself and so no
     * extra object creation is needed.
     */
    private static final class HashedWheelBucket {
        // Used for the linked-list datastructure
        private HashedWheelTimeout head;// 指向双向链表中的第一个 timeout
        private HashedWheelTimeout tail;// 指向双向链表中的最后一个 timeout

        /**
         * Add {@link HashedWheelTimeout} to this bucket. 尾插
         */
        public void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * Expire all {@link HashedWheelTimeout}s for the given {@code deadline}.
         */
        public void expireTimeouts(long deadline) {
            HashedWheelTimeout timeout = head;

            // process all timeouts
            while (timeout != null) { // bucket 不为空
                HashedWheelTimeout next = timeout.next;
                if (timeout.remainingRounds <= 0) { // 属于当前时钟周期
                    next = remove(timeout); // 从 bucket 中删除
                    if (timeout.deadline <= deadline) {
                        // 时间轮的 taskExecutor 负责执行延时任务
                        timeout.expire();
                    } else {
                        // The timeout was placed into a wrong slot. This should never happen.
                        throw new IllegalStateException(String.format(
                                "timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                } else if (timeout.isCancelled()) {
                    next = remove(timeout);
                } else {
                    timeout.remainingRounds --;
                }
                timeout = next;
            }
        }

        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) { // timeout 不是第一个元素
                timeout.prev.next = next;
            }
            if (timeout.next != null) { // timeout 不是最后一个元素
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * Clear this bucket and return all not expired / cancelled {@link Timeout}s.
         */
        public void clearTimeouts(Set<Timeout> set) {
            for (;;) {
                // 取出 bucket 中的第一个 timeout
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                // 将没来得及执行并且没有被取消的延时任务添加到 set 中
                set.add(timeout);
            }
        }
        // 从头开始 poll
        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head =  null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }
}
