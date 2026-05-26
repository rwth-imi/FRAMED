package com.framed.streamer.dispatcher;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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
