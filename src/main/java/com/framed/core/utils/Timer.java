package com.framed.core.utils;

import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;


/**
 * A utility class for scheduling tasks with delays or at fixed intervals using a
 * {@link ScheduledExecutorService}.
 *
 * <p>This class provides instance-level scheduling, meaning each {@code Timer} object
 * manages its own executor. This avoids global state issues and allows independent
 * lifecycle management for different components.</p>
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Schedule a one-time task after a specified delay.</li>
 *   <li>Schedule a periodic task at a fixed interval.</li>
 *   <li>Shutdown the scheduler when no longer needed to free resources.</li>
 * </ul>
 *
 * <p><b>Example usage:</b></p>
 * <pre>{@code
 * Timer timer = new Timer();
 * timer.setTimer(1000, () -> System.out.println("Executed after 1 second"));
 * timer.setPeriodic(5000, () -> System.out.println("Executed every 5 seconds"));
 *
 * // Later, when done:
 * timer.shutdown();
 * }</pre>
 *
 * <p><b>Note:</b> Always call {@link #shutdown()} when the timer is no longer needed
 * to prevent resource leaks.</p>
 */
public class Timer {

  public static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

  /**
   * A single-threaded scheduled executor used for task scheduling.
   */
  private final ScheduledExecutorService scheduler =  Executors.newScheduledThreadPool(1);

  /**
   * Schedules a one-time task to execute after the specified delay.
   *
   * @param delayMillis the delay in milliseconds before executing the task
   * @param task        the {@link Runnable} task to execute
   */

  public void setTimer(long delayMillis, Runnable task) {
    scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Schedules a periodic task to execute at a fixed interval.
   *
   * @param intervalMillis the interval in milliseconds between consecutive executions
   * @param task           the {@link Runnable} task to execute periodically
   */
  public void setPeriodic(long intervalMillis, Runnable task) {
    scheduler.scheduleAtFixedRate(task, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Shuts down the scheduler and stops accepting new tasks.
   * <p>Pending tasks will still execute unless {@link ScheduledExecutorService#shutdownNow()} is used.</p>
   */
  public void shutdown() {
    scheduler.shutdown();
  }
}

