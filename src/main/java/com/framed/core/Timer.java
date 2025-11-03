package com.framed.core;

import java.util.concurrent.*;

public class Timer {
  private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  public static void setTimer(long delayMillis, Runnable task) {
    scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
  }

  public static ScheduledFuture<?> setPeriodic(long intervalMillis, Runnable task) {
    return scheduler.scheduleAtFixedRate(task, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
  }

  public static void shutdown() {
    scheduler.shutdown();
  }

}
