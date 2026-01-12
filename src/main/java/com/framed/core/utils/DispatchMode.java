package com.framed.core.utils;

public enum DispatchMode {
  SEQUENTIAL,       // All handlers run sequentially on the caller thread
  PARALLEL,         // Each handler runs in its own thread (via a shared pool)
  PER_HANDLER       // Each handler has its own single-thread executor (ordered per handler    PER_HANDLER       // Each handler has its own single-thread executor (ordered per handler)
}
