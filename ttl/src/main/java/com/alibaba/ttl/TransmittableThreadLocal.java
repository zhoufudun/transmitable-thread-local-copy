package com.alibaba.ttl;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link TransmittableThreadLocal} can transmit value from the thread of submitting task to the thread of executing task.
 * <p>
 * <b>Note</b>:<br>
 * {@link TransmittableThreadLocal} extends {@link InheritableThreadLocal},
 * so {@link TransmittableThreadLocal} first is a {@link InheritableThreadLocal}.<br>
 * If the <b>inheritable</b> ability from {@link InheritableThreadLocal} has <b>potential leaking problem</b>,
 * you can disable the <b>inheritable</b> ability:
 * <p>
 * ❶ by wrapping thread factory using method
 * {@link com.alibaba.ttl.threadpool.TtlExecutors#getDisableInheritableThreadFactory(java.util.concurrent.ThreadFactory)} /
 * {@link com.alibaba.ttl.threadpool.TtlForkJoinPoolHelper#getDefaultDisableInheritableForkJoinWorkerThreadFactory()}
 * for thread pooling components({@link java.util.concurrent.ThreadPoolExecutor}, {@link java.util.concurrent.ForkJoinPool}).
 * Inheritable feature in thread pooling components should <b>never</b> happen,
 * because threads in thread pooling components is pre-created and pooled, these threads is neutral for biz logic/data.
 * <br>
 * You can turn on "disable inheritable for thread pool" by {@link com.alibaba.ttl.threadpool.agent.TtlAgent}
 * so as to wrap thread factory for thread pooling components
 * ({@link java.util.concurrent.ThreadPoolExecutor}, {@link java.util.concurrent.ForkJoinPool}) automatically and transparently.
 * <p>
 * ❷ or by overriding method {@link #childValue(Object)}.
 * Whether the value should be inheritable or not can be controlled by the data owner,
 * disable it <b>carefully</b> when data owner have a clear idea.
 * <pre> {@code TransmittableThreadLocal<String> transmittableThreadLocal = new TransmittableThreadLocal<String>() {
 *     protected String childValue(String parentValue) {
 *         return initialValue();
 *     }
 * }}</pre>
 * <p>
 * More discussion about "disable the <b>inheritable</b> ability"
 * see <a href="https://github.com/alibaba/transmittable-thread-local/issues/100">
 *     issue #100: disable Inheritable when it's not necessary and buggy</a>.
 *
 * @author Jerry Lee (oldratlee at gmail dot com)
 * @author Yang Fang (snoop dot fy at gmail dot com)
 * @see com.alibaba.ttl.TtlRunnable
 * @see TtlCallable
 * @see com.alibaba.ttl.threadpool.TtlExecutors#getDefaultDisableInheritableThreadFactory()
 * @see com.alibaba.ttl.threadpool.TtlExecutors#getDisableInheritableThreadFactory(java.util.concurrent.ThreadFactory)
 * @see com.alibaba.ttl.threadpool.TtlForkJoinPoolHelper#getDefaultDisableInheritableForkJoinWorkerThreadFactory()
 * @see com.alibaba.ttl.threadpool.TtlForkJoinPoolHelper#getDisableInheritableForkJoinWorkerThreadFactory(java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory)
 * @since 0.10.0
 */
public class TransmittableThreadLocal<T> extends InheritableThreadLocal<T> {
    private static final Logger logger = Logger.getLogger(TransmittableThreadLocal.class.getName());

    /**
     * Computes the value for this transmittable thread-local variable
     * as a function of the source thread's value at the time the task
     * Object is created.  This method is called from {@link com.alibaba.ttl.TtlRunnable} or
     * {@link TtlCallable} when it create, before the task is started.
     * <p>
     * This method merely returns reference of its source thread value, and should be overridden
     * if a different behavior is desired.
     *
     * @since 1.0.0
     */
    protected T copy(T parentValue) {
        return parentValue;
    }

    /**
     * Callback method before task object({@link com.alibaba.ttl.TtlRunnable}/{@link TtlCallable}) execute.
     * <p>
     * Default behavior is do nothing, and should be overridden
     * if a different behavior is desired.
     * <p>
     * Do not throw any exception, just ignored.
     *
     * @since 1.2.0
     */
    protected void beforeExecute() {
    }

    /**
     * Callback method after task object({@link com.alibaba.ttl.TtlRunnable}/{@link TtlCallable}) execute.
     * <p>
     * Default behavior is do nothing, and should be overridden
     * if a different behavior is desired.
     * <p>
     * Do not throw any exception, just ignored.
     *
     * @since 1.2.0
     */
    protected void afterExecute() {
    }

    /**
     * see {@link InheritableThreadLocal#get()}
     */
    @Override
    public final T get() {
        T value = super.get();
        if (null != value) addValue();
        return value;
    }

    /**
     * see {@link InheritableThreadLocal#set}
     */
    @Override
    public final void set(T value) {
        super.set(value);
        // may set null to remove value
        if (null == value) {
            removeValue();
        } else {
            addValue();
        }
    }

    /**
     * see {@link InheritableThreadLocal#remove()}
     */
    @Override
    public final void remove() {
        removeValue();
        super.remove();
    }

    private void superRemove() {
        super.remove();
    }

    private T copyValue() {
        return copy(get());
    }

    // Note about holder:
    // 1. The value of holder is type Map<TransmittableThreadLocal<?>, ?> (WeakHashMap implementation),
    //    but it is used as *set*.
    // 2. WeakHashMap support null value.
    private static InheritableThreadLocal<Map<TransmittableThreadLocal<?>, ?>> holder =
            new InheritableThreadLocal<Map<TransmittableThreadLocal<?>, ?>>() {
                @Override
                protected Map<TransmittableThreadLocal<?>, ?> initialValue() {
                    return new WeakHashMap<TransmittableThreadLocal<?>, Object>();
                }

                @Override
                protected Map<TransmittableThreadLocal<?>, ?> childValue(Map<TransmittableThreadLocal<?>, ?> parentValue) {
                    return new WeakHashMap<TransmittableThreadLocal<?>, Object>(parentValue);
                }
            };

    private void addValue() {
        if (!holder.get().containsKey(this)) {
            holder.get().put(this, null); // WeakHashMap supports null value.
        }
    }

    private void removeValue() {
        holder.get().remove(this);
    }

    private static void doExecuteCallback(boolean isBefore) {
        for (Map.Entry<TransmittableThreadLocal<?>, ?> entry : holder.get().entrySet()) {
            TransmittableThreadLocal<?> threadLocal = entry.getKey();

            try {
                if (isBefore) {
                    threadLocal.beforeExecute();
                } else {
                    threadLocal.afterExecute();
                }
            } catch (Throwable t) {
                if (logger.isLoggable(Level.WARNING)) {
                    logger.log(Level.WARNING, "TTL exception when " + (isBefore ? "beforeExecute" : "afterExecute") + ", cause: " + t.toString(), t);
                }
            }
        }
    }

    /**
     * Debug only method!
     */
    static void dump(@Nullable String title) {
        if (title != null && title.length() > 0) {
            System.out.printf("Start TransmittableThreadLocal[%s] Dump...\n", title);
        } else {
            System.out.println("Start TransmittableThreadLocal Dump...");
        }

        for (Map.Entry<TransmittableThreadLocal<?>, ?> entry : holder.get().entrySet()) {
            final TransmittableThreadLocal<?> key = entry.getKey();
            System.out.println(key.get());
        }
        System.out.println("TransmittableThreadLocal Dump end!");
    }

    /**
     * Debug only method!
     */
    static void dump() {
        dump(null);
    }

    /**
     * {@link Transmitter} transmit all {@link TransmittableThreadLocal} values of current thread to
     * other thread by static methods {@link #capture()} =&gt; {@link #replay(Object)} =&gt; {@link #restore(Object)} (aka {@code CRR} operation).
     * <p>
     * {@link Transmitter} is <b><i>internal</i></b> manipulation api for <b><i>framework/middleware integration</i></b>;
     * In general, you will <b><i>never</i></b> use it in the <i>biz/application code</i>!
     * <p>
     * Below is the example code:
     *
     * <pre><code>
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread A, capture all TransmittableThreadLocal values of thread A
     * ///////////////////////////////////////////////////////////////////////////
     *
     * Object captured = Transmitter.capture(); // (1)
     *
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread B
     * ///////////////////////////////////////////////////////////////////////////
     *
     * // replay all TransmittableThreadLocal values from thread A
     * Object backup = Transmitter.replay(captured); // (2)
     * try {
     *     // your biz logic, run with the TransmittableThreadLocal values of thread B
     *     System.out.println("Hello");
     *     // ...
     *     return "World";
     * } finally {
     *     // restore the TransmittableThreadLocal of thread B when replay
     *     Transmitter.restore(backup); (3)
     * }
     * </code></pre>
     * <p>
     * see the implementation code of {@link com.alibaba.ttl.TtlRunnable} and {@link TtlCallable} for more actual code sample.
     * <hr>
     * Of course, {@link #replay(Object)} and {@link #restore(Object)} operation can be simplified
     * by util methods {@link #runCallableWithCaptured(Object, Callable)} or {@link #runSupplierWithCaptured(Object, Supplier)}
     * and the adorable {@code Java 8 lambda syntax}.
     * <p>
     * Below is the example code:
     *
     * <pre><code>
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread A, capture all TransmittableThreadLocal values of thread A
     * ///////////////////////////////////////////////////////////////////////////
     *
     * Object captured = Transmitter.capture(); // (1)
     *
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread B
     * ///////////////////////////////////////////////////////////////////////////
     *
     * String result = runSupplierWithCaptured(captured, () -&gt; {
     *      // your biz logic, run with the TransmittableThreadLocal values of thread A
     *      System.out.println("Hello");
     *      ...
     *      return "World";
     * }); // (2) + (3)
     * </code></pre>
     * <p>
     * The reason of providing 2 util methods is the different {@code throws Exception} type so as to satisfy your biz logic({@code lambda}):
     * <ol>
     * <li>{@link #runCallableWithCaptured(Object, Callable)}: {@code throws Exception}</li>
     * <li>{@link #runSupplierWithCaptured(Object, Supplier)}: No {@code throws}</li>
     * </ol>
     * <p>
     * If you need the different {@code throws Exception} type,
     * you can define your own util method(function interface({@code lambda})) with your own {@code throws Exception} type.
     *
     * @author Yang Fang (snoop dot fy at gmail dot com)
     * @author Jerry Lee (oldratlee at gmail dot com)
     * @see TtlRunnable
     * @see TtlCallable
     * @since 2.3.0
     */
    public static class Transmitter {
        /**
         * Capture all {@link TransmittableThreadLocal} values in current thread.
         *
         * @return the captured {@link TransmittableThreadLocal} values
         * @since 2.3.0
         */
        @Nonnull
        public static Object capture() {
            Map<TransmittableThreadLocal<?>, Object> captured = new HashMap<TransmittableThreadLocal<?>, Object>();
            /**
             * holder.get(): 获取当前线程（当前线程指的是提交任务到线程池的线程，也可理解为是父线程）中使用中的ThreadLocal
             */
            for (TransmittableThreadLocal<?> threadLocal : holder.get().keySet()) {
                captured.put(threadLocal, threadLocal.copyValue());
            }
            return captured;
        }

        /**
         * Replay the captured {@link TransmittableThreadLocal} values from {@link #capture()},
         * and return the backup {@link TransmittableThreadLocal} values in current thread before replay.
         *
         * @param captured captured {@link TransmittableThreadLocal} values from other thread from {@link #capture()}
         * @return the backup {@link TransmittableThreadLocal} values before replay
         * @see #capture()
         * @since 2.3.0
         *
         * 1、备份本线程自己的数据
         * 2、恢复父线程的数据到本线程
         * 3、返回本线程数据的备份，之后本线程执行完毕，需要恢复本线程的数据
         */
        @Nonnull
        public static Object replay(@Nonnull Object captured) {
            @SuppressWarnings("unchecked")
            Map<TransmittableThreadLocal<?>, Object> capturedMap = (Map<TransmittableThreadLocal<?>, Object>) captured; // 父线程本地数据
            Map<TransmittableThreadLocal<?>, Object> backup = new HashMap<TransmittableThreadLocal<?>, Object>(); //线程池中处理本次任务的线程中原先存在的本地线程变量
            /**
             * 遍历子线程原先自己的本地线程变量
             */
            for (Iterator<? extends Map.Entry<TransmittableThreadLocal<?>, ?>> iterator = holder.get().entrySet().iterator();
                 iterator.hasNext(); ) {
                Map.Entry<TransmittableThreadLocal<?>, ?> next = iterator.next();
                TransmittableThreadLocal<?> threadLocal = next.getKey();
                /**
                 * 在执行任务之前，备份一下本线程之前原先自己的本地线程变量数据
                 */
                // backup
                backup.put(threadLocal, threadLocal.get());

                // clear the TTL values that is not in captured
                // avoid the extra TTL values after replay when run task
                /**
                 * 根据父线程的本地变量来重放当前线程，如果父线程中不包含的threadlocal对象，将从本地线程变量中移除。
                 *
                 * 把父线程的数据，重新生成存储到本线程本地变量中
                 */
                if (!capturedMap.containsKey(threadLocal)) {  // 不是很理解？？？
                    iterator.remove();
                    threadLocal.superRemove();
                }
            }

            // set values to captured TTL
            /**
             * 此时capturedMap
             */
            setTtlValuesTo(capturedMap); // 遍历父线程中的本地线程变量，在子线程中重新执行一次threadlocal.set方法

            // call beforeExecute callback
            doExecuteCallback(true); //执行beforeExecute()钩子函数

            return backup; //返回线程池原线程的本地线程变量，供本次调用后恢复上下文环境
        }

        /**
         * Clear all {@link TransmittableThreadLocal} values in current thread,
         * and return the backup {@link TransmittableThreadLocal} values in current thread before clear.
         *
         * @return the backup {@link TransmittableThreadLocal} values before clear
         * @since 2.9.0
         */
        @Nonnull
        public static Object clear() {
            return replay(Collections.emptyMap());
        }

        /**
         * Restore the backup {@link TransmittableThreadLocal} values from {@link #replay(Object)}/{@link #clear()}.
         *
         * @param backup the backup {@link TransmittableThreadLocal} values from {@link #replay(Object)}/{@link #clear()}
         * @see #replay(Object)
         * @see #clear()
         * @since 2.3.0
         */
        public static void restore(@Nonnull Object backup) {
            @SuppressWarnings("unchecked")
            Map<TransmittableThreadLocal<?>, Object> backupMap = (Map<TransmittableThreadLocal<?>, Object>) backup; //获取备份好的线程本地上下文
            // call afterExecute callback
            doExecuteCallback(false);// 执行afterExecute()钩子函数
            /**
             * 遍历本地线程变量，将不属于backUpMap中存在的线程本地上下文移除
             */
            for (Iterator<? extends Map.Entry<TransmittableThreadLocal<?>, ?>> iterator = holder.get().entrySet().iterator();
                 iterator.hasNext(); ) {
                Map.Entry<TransmittableThreadLocal<?>, ?> next = iterator.next();
                TransmittableThreadLocal<?> threadLocal = next.getKey();

                // clear the TTL values that is not in backup
                // avoid the extra TTL values after restore
                if (!backupMap.containsKey(threadLocal)) {
                    iterator.remove();
                    threadLocal.superRemove();
                }
            }

            // restore TTL values
            /**
             * 遍历备份的本地线程本地，在本地线程中重新执行threadlocal#set方法，实现线程本地变量的还原
             */
            setTtlValuesTo(backupMap);
        }

        /**
         * 遍历父线程中的本地线程变量，在子线程中重新执行一次threadlocal.set方法
         * @param ttlValues
         */
        private static void setTtlValuesTo(@Nonnull Map<TransmittableThreadLocal<?>, Object> ttlValues) {
            for (Map.Entry<TransmittableThreadLocal<?>, Object> entry : ttlValues.entrySet()) {
                @SuppressWarnings("unchecked")
                TransmittableThreadLocal<Object> threadLocal = (TransmittableThreadLocal<Object>) entry.getKey();
                threadLocal.set(entry.getValue());
            }
        }

        /**
         * Util method for simplifying {@link #replay(Object)} and {@link #restore(Object)} operation.
         *
         * @param captured captured {@link TransmittableThreadLocal} values from other thread from {@link #capture()}
         * @param bizLogic biz logic
         * @param <R>      the return type of biz logic
         * @return the return value of biz logic
         * @see #capture()
         * @see #replay(Object)
         * @see #restore(Object)
         * @since 2.3.1
         */
        public static <R> R runSupplierWithCaptured(@Nonnull Object captured, @Nonnull Supplier<R> bizLogic) {
            Object backup = replay(captured);
            try {
                return bizLogic.get();
            } finally {
                restore(backup);
            }
        }

        /**
         * Util method for simplifying {@link #clear()} and {@link #restore(Object)} operation.
         *
         * @param bizLogic biz logic
         * @param <R>      the return type of biz logic
         * @return the return value of biz logic
         * @see #clear()
         * @see #restore(Object)
         * @since 2.9.0
         */
        public static <R> R runSupplierWithClear(@Nonnull Supplier<R> bizLogic) {
            Object backup = clear();
            try {
                return bizLogic.get();
            } finally {
                restore(backup);
            }
        }

        /**
         * Util method for simplifying {@link #replay(Object)} and {@link #restore(Object)} operation.
         *
         * @param captured captured {@link TransmittableThreadLocal} values from other thread from {@link #capture()}
         * @param bizLogic biz logic
         * @param <R>      the return type of biz logic
         * @return the return value of biz logic
         * @throws Exception exception threw by biz logic
         * @see #capture()
         * @see #replay(Object)
         * @see #restore(Object)
         * @since 2.3.1
         */
        public static <R> R runCallableWithCaptured(@Nonnull Object captured, @Nonnull Callable<R> bizLogic) throws Exception {
            Object backup = replay(captured);
            try {
                return bizLogic.call();
            } finally {
                restore(backup);
            }
        }

        /**
         * Util method for simplifying {@link #clear()} and {@link #restore(Object)} operation.
         *
         * @param bizLogic biz logic
         * @param <R>      the return type of biz logic
         * @return the return value of biz logic
         * @throws Exception exception threw by biz logic
         * @see #clear()
         * @see #restore(Object)
         * @since 2.9.0
         */
        public static <R> R runCallableWithClear(@Nonnull Callable<R> bizLogic) throws Exception {
            Object backup = clear();
            try {
                return bizLogic.call();
            } finally {
                restore(backup);
            }
        }

        private Transmitter() {
            throw new InstantiationError("Must not instantiate this class");
        }
    }
}
