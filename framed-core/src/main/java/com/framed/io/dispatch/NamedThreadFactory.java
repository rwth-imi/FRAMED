package com.framed.io.dispatch;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ThreadFactory} that produces daemon threads named {@code <base>-<n>}, where
 * {@code n} is an incrementing counter. Used to give dispatcher worker threads stable,
 * recognizable names.
 */
public final class NamedThreadFactory implements ThreadFactory {
    private final String base;
    private final AtomicInteger n = new AtomicInteger(1);

    NamedThreadFactory(String base) {
        this.base = base;
    }

    @Override public Thread newThread(Runnable r) {
        Thread t = new Thread(r, base + "-" + n.getAndIncrement());
        t.setDaemon(true);
        return t;
    }
}
