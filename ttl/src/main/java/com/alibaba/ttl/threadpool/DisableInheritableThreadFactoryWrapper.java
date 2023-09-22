package com.alibaba.ttl.threadpool;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.threadpool.DisableInheritableThreadFactory;

import javax.annotation.Nonnull;
import java.util.concurrent.ThreadFactory;

/**
 * @author Jerry Lee (oldratlee at gmail dot com)
 * @since 2.10.0
 */
class DisableInheritableThreadFactoryWrapper implements DisableInheritableThreadFactory {
    final ThreadFactory threadFactory;

    public DisableInheritableThreadFactoryWrapper(@Nonnull ThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
    }

    @Override
    public Thread newThread(Runnable r) {
        final Object backup = TransmittableThreadLocal.Transmitter.clear();
        try {
            return threadFactory.newThread(r);
        } finally {
            TransmittableThreadLocal.Transmitter.restore(backup);
        }
    }

    @Nonnull
    @Override
    public ThreadFactory unwrap() {
        return threadFactory;
    }
}
