package com.alibaba.ttl;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.alibaba.ttl.TransmittableThreadLocal.Transmitter.*;

/**
 * {@link TtlRunnable} decorate {@link Runnable}, so as to get {@link TransmittableThreadLocal}
 * and transmit it to the time of {@link Runnable} execution, needed when use {@link Runnable} to thread pool.
 * <p>
 * Use factory methods {@link #get} / {@link #gets} to create instance.
 *
 * @author Jerry Lee (oldratlee at gmail dot com)
 * @see com.alibaba.ttl.threadpool.TtlExecutors
 * @see java.util.concurrent.Executor
 * @see java.util.concurrent.ExecutorService
 * @see java.util.concurrent.ThreadPoolExecutor
 * @see java.util.concurrent.ScheduledThreadPoolExecutor
 * @see java.util.concurrent.Executors
 * @since 0.9.0
 */
public final class TtlRunnable implements Runnable, com.alibaba.ttl.TtlEnhanced {
    private final AtomicReference<Object> capturedRef;
    private final Runnable runnable;
    private final boolean releaseTtlValueReferenceAfterRun;

    /**
     * 第一步：子线程提交任务：在向线程池提交任务时，会先捕获父线程(父线程指的是：提交任务到线程池的线程)中的本地环境变量
     *
     * @param runnable
     * @param releaseTtlValueReferenceAfterRun
     */
    private TtlRunnable(@Nonnull Runnable runnable, boolean releaseTtlValueReferenceAfterRun) {
        this.capturedRef = new AtomicReference<Object>(capture());
        this.runnable = runnable;
        this.releaseTtlValueReferenceAfterRun = releaseTtlValueReferenceAfterRun;
    }

    /**
     * wrap method {@link Runnable#run()}.
     *
     *  第二步：子线程执行
     */
    @Override
    public void run() {
        //1. 获取快照，也就是Snapshot()
        //2. 将快照中的值设置到当前线程的上下文中（也就是TransmittableThreadLocal或者ThreadLocal）
        //3. 返回backup，就是在设置之前，当前线程的快照信息
        /**
         * 代码到这里之前就是子线程开始了，之前已经执行过TtlRunnable(@Nonnull Runnable runnable, boolean releaseTtlValueReferenceAfterRun)，
         * 此时的capturedRef就是父线程的快照（理解为：父线程之前被哪些TransmittableThreadLocal访问过，并且与之对于的缓存的值是什么）
         */
        Object captured = capturedRef.get();// 获取父线程的本地环境变量
        if (captured == null || releaseTtlValueReferenceAfterRun && !capturedRef.compareAndSet(captured, null)) {
            throw new IllegalStateException("TTL value reference is released after run!");
        }

        // "重放"父线程的本地环境变量，即使用从父线程中捕获过来的上下文环境，在子线程中重新执行一遍，并返回原先存在与子线程中的上下文环境变量
        Object backup = replay(captured); // 子线程（本线程）继承父线程的上下文，并且把子线程的上下文备份一下
        try {
            runnable.run(); // 执行子线程自己的业务逻辑
        } finally {
            restore(backup); // 恢复子线程的备份上下文
            //4.将设置的当前线程快照信息给重新设置回去
            restore(backup);
        }
    }

    /**
     * return original/unwrapped {@link Runnable}.
     */
    @Nonnull
    public Runnable getRunnable() {
        return runnable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TtlRunnable that = (TtlRunnable) o;

        return runnable.equals(that.runnable);
    }

    @Override
    public int hashCode() {
        return runnable.hashCode();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + " - " + runnable.toString();
    }

    /**
     * Factory method, wrap input {@link Runnable} to {@link TtlRunnable}.
     *
     * @param runnable input {@link Runnable}. if input is {@code null}, return {@code null}.
     * @return Wrapped {@link Runnable}
     * @throws IllegalStateException when input is {@link TtlRunnable} already.
     */
    @Nullable
    public static TtlRunnable get(@Nullable Runnable runnable) {
        return get(runnable, false, false);
    }

    /**
     * Factory method, wrap input {@link Runnable} to {@link TtlRunnable}.
     *
     * @param runnable                         input {@link Runnable}. if input is {@code null}, return {@code null}.
     * @param releaseTtlValueReferenceAfterRun release TTL value reference after run, avoid memory leak even if {@link TtlRunnable} is referred.
     * @return Wrapped {@link Runnable}
     * @throws IllegalStateException when input is {@link TtlRunnable} already.
     */
    @Nullable
    public static TtlRunnable get(@Nullable Runnable runnable, boolean releaseTtlValueReferenceAfterRun) {
        return get(runnable, releaseTtlValueReferenceAfterRun, false);
    }

    /**
     * Factory method, wrap input {@link Runnable} to {@link TtlRunnable}.
     *
     * @param runnable                         input {@link Runnable}. if input is {@code null}, return {@code null}.
     * @param releaseTtlValueReferenceAfterRun release TTL value reference after run, avoid memory leak even if {@link TtlRunnable} is referred.
     * @param idempotent                       is idempotent mode or not. if {@code true}, just return input {@link Runnable} when it's {@link TtlRunnable},
     *                                         otherwise throw {@link IllegalStateException}.
     *                                         <B><I>Caution</I></B>: {@code true} will cover up bugs! <b>DO NOT</b> set, only when you know why.
     * @return Wrapped {@link Runnable}
     * @throws IllegalStateException when input is {@link TtlRunnable} already and not idempotent.
     */
    @Nullable
    public static TtlRunnable get(@Nullable Runnable runnable, boolean releaseTtlValueReferenceAfterRun, boolean idempotent) {
        if (null == runnable) return null;

        if (runnable instanceof TtlEnhanced) {
            // avoid redundant decoration, and ensure idempotency
            if (idempotent) return (TtlRunnable) runnable;
            else throw new IllegalStateException("Already TtlRunnable!");
        }
        return new TtlRunnable(runnable, releaseTtlValueReferenceAfterRun);
    }

    /**
     * wrap input {@link Runnable} Collection to {@link TtlRunnable} Collection.
     *
     * @param tasks task to be wrapped. if input is {@code null}, return {@code null}.
     * @return wrapped tasks
     * @throws IllegalStateException when input is {@link TtlRunnable} already.
     */
    @Nonnull
    public static List<TtlRunnable> gets(@Nullable Collection<? extends Runnable> tasks) {
        return gets(tasks, false, false);
    }

    /**
     * wrap input {@link Runnable} Collection to {@link TtlRunnable} Collection.
     *
     * @param tasks                            task to be wrapped. if input is {@code null}, return {@code null}.
     * @param releaseTtlValueReferenceAfterRun release TTL value reference after run, avoid memory leak even if {@link TtlRunnable} is referred.
     * @return wrapped tasks
     * @throws IllegalStateException when input is {@link TtlRunnable} already.
     */
    @Nonnull
    public static List<TtlRunnable> gets(@Nullable Collection<? extends Runnable> tasks, boolean releaseTtlValueReferenceAfterRun) {
        return gets(tasks, releaseTtlValueReferenceAfterRun, false);
    }

    /**
     * wrap input {@link Runnable} Collection to {@link TtlRunnable} Collection.
     *
     * @param tasks                            task to be wrapped. if input is {@code null}, return {@code null}.
     * @param releaseTtlValueReferenceAfterRun release TTL value reference after run, avoid memory leak even if {@link TtlRunnable} is referred.
     * @param idempotent                       is idempotent mode or not. if {@code true}, just return input {@link Runnable} when it's {@link TtlRunnable},
     *                                         otherwise throw {@link IllegalStateException}.
     *                                         <B><I>Caution</I></B>: {@code true} will cover up bugs! <b>DO NOT</b> set, only when you know why.
     * @return wrapped tasks
     * @throws IllegalStateException when input is {@link TtlRunnable} already and not idempotent.
     */
    @Nonnull
    public static List<TtlRunnable> gets(@Nullable Collection<? extends Runnable> tasks, boolean releaseTtlValueReferenceAfterRun, boolean idempotent) {
        if (null == tasks) return Collections.emptyList();

        List<TtlRunnable> copy = new ArrayList<TtlRunnable>();
        for (Runnable task : tasks) {
            copy.add(TtlRunnable.get(task, releaseTtlValueReferenceAfterRun, idempotent));
        }
        return copy;
    }

    /**
     * Unwrap {@link TtlRunnable} to the original/underneath one.
     * <p>
     * this method is {@code null}-safe, when input {@code Runnable} parameter is {@code null}, return {@code null};
     * if input {@code Runnable} parameter is not a {@link TtlRunnable} just return input {@code Runnable}.
     *
     * @since 2.10.2
     */
    @Nullable
    public static Runnable unwrap(@Nullable Runnable runnable) {
        if (!(runnable instanceof TtlRunnable)) return runnable;
        else return ((TtlRunnable) runnable).getRunnable();
    }

    /**
     * Unwrap {@link TtlRunnable} to the original/underneath one for collection.
     * <p>
     * Invoke {@link #unwrap(Runnable)} for each element in input collection.
     * <p>
     * This method is {@code null}-safe, when input {@code Runnable} parameter is {@code null}, return a empty list.
     *
     * @see #unwrap(Runnable)
     * @since 2.10.2
     */
    @Nonnull
    public static List<Runnable> unwraps(@Nullable Collection<? extends Runnable> tasks) {
        if (null == tasks) return Collections.emptyList();

        List<Runnable> copy = new ArrayList<Runnable>();
        for (Runnable task : tasks) {
            if (!(task instanceof TtlRunnable)) copy.add(task);
            else copy.add(((TtlRunnable) task).getRunnable());
        }
        return copy;
    }
}
