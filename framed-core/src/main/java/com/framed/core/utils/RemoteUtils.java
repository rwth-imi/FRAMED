// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.core.utils;

import com.framed.core.remote.RemoteMessage;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Utility methods for parsing and dispatching JSON-encoded remote messages to registered
 * handlers. Used by transport-backed event bus implementations to decode incoming wire
 * messages and route their payloads to the appropriate consumers.
 */
public class RemoteUtils {
  private RemoteUtils() {
    throw new IllegalStateException("Utility class");
  }

  /**
   * Parses a JSON-encoded remote message into a {@link RemoteMessage}.
   *
   * @param jsonStr the JSON string containing {@code address}, {@code payload} and {@code type}
   * @return the decoded remote message
   */
  public static RemoteMessage parseMessage(String jsonStr) {
    JSONObject json = new JSONObject(jsonStr);
    String address = json.getString("address");
    Object payload = json.get("payload");
    String type = json.getString("type");
    return new RemoteMessage(address, payload, type);
  }

  /**
   * Parses a JSON message and submits it to registered handlers on a shared pool.
   *
   * @param jsonStr    the JSON string representing the message to parse and dispatch
   * @param handlers   map of address to the handlers registered for that address
   * @param workerPool the worker pool of the transport used to run each handler invocation
   * @deprecated does not preserve order: consecutive messages for one handler run as independent
   *             tasks and can overtake each other, which a {@code Reactor}'s logical-time gate turns
   *             into silently discarded messages. Use
   *             {@link #parseAndDispatch(String, Map, Map)}; no transport calls this method any more.
   */
  @Deprecated
  public static void parseAndDispatchAsync(String jsonStr, Map<String, List<Consumer<Object>>> handlers, ExecutorService workerPool) {
    RemoteMessage result = RemoteUtils.parseMessage(jsonStr);

    List<Consumer<Object>> list = handlers.get(result.address());
    if (list != null) {
      if ("send".equals(result.type()) && !list.isEmpty()) {
        workerPool.submit(() -> list.get(0).accept(result.payload()));
      } else {
        for (Consumer<Object> handler : list) {
          workerPool.submit(() -> handler.accept(result.payload()));
        }
      }
    }
  }

  /**
   * Parses a JSON message and hands it to the handlers registered for its address, in order.
   *
   * <p>A {@code "send"} message goes to the first handler registered for the address only
   * (point-to-point); any other type goes to every handler (broadcast).</p>
   *
   * <p>Each handler runs on its own single-thread executor, created on first use and cached in
   * {@code handlerExecutors}. A handler therefore receives the messages of one connection in the
   * order this method is called with them, while different handlers proceed concurrently — the
   * guarantee {@code DispatchMode.PER_HANDLER} gives local dispatch. Callers must call this method
   * from one thread per connection (the transports' reader threads do), since order across callers
   * is the order they reach the executor.</p>
   *
   * @param jsonStr          the JSON string representing the message to parse and dispatch
   * @param handlers         map of address to the handlers registered for that address
   * @param handlerExecutors map of handler to its dedicated single-thread executor, populated
   *                         on demand for handlers that do not yet have one
   */
  public static void parseAndDispatch(String jsonStr,
                                      Map<String, List<Consumer<Object>>> handlers,
                                      Map<Consumer<Object>, ExecutorService> handlerExecutors) {
    RemoteMessage result = RemoteUtils.parseMessage(jsonStr);
    List<Consumer<Object>> list = handlers.get(result.address());
    if (list == null || list.isEmpty()) {
      return;
    }
    if ("send".equals(result.type())) {
      Consumer<Object> first = list.get(0);
      handlerExecutor(handlerExecutors, first).submit(() -> first.accept(result.payload()));
      return;
    }
    for (Consumer<Object> handler : list) {
      handlerExecutor(handlerExecutors, handler).submit(() -> handler.accept(result.payload()));
    }
  }

  /**
   * Returns the single-thread executor of a handler, creating it on first use.
   *
   * @param handlerExecutors the transport's handler-to-executor map
   * @param handler          the handler
   * @return the handler's executor
   */
  public static ExecutorService handlerExecutor(Map<Consumer<Object>, ExecutorService> handlerExecutors,
                                                Consumer<Object> handler) {
    return handlerExecutors.computeIfAbsent(handler, h ->
            Executors.newSingleThreadExecutor(r -> new Thread(r, "Handler-" + h.hashCode())));
  }
}
