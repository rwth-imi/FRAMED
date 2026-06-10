package com.framed.core.utils;

/**
 * Strategy for how an event bus invokes the handlers registered for an address.
 *
 * <p>Used by event bus implementations (such as {@code LocalEventBus} and
 * {@code SocketEventBus}) to decide on which thread each registered handler runs.</p>
 */
public enum DispatchMode {
  /** All handlers run sequentially, inline on the calling (publishing) thread. */
  SEQUENTIAL,
  /** Each handler invocation is submitted to a shared thread pool and runs concurrently. */
  PARALLEL,
  /** Each handler gets its own single-thread executor, preserving per-handler FIFO ordering. */
  PER_HANDLER
}
