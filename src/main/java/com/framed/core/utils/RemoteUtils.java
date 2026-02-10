package com.framed.core.utils;

import com.framed.core.remote.RemoteMessage;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class RemoteUtils {
  private RemoteUtils() {
    throw new IllegalStateException("Utility class");
  }

  @NotNull
  public static RemoteMessage parseMessage(String jsonStr) {
    JSONObject json = new JSONObject(jsonStr);
    String address = json.getString("address");
    Object payload = json.get("payload");
    String type = json.getString("type");
    return new RemoteMessage(address, payload, type);
  }

  /**
   * Parses a JSON message and submits it to registered handlers, creating a thread per handler.
   *
   * @param jsonStr    the JSON string representing the message to parse and dispatch
   * @param workerPool the workerPool of the Transport
   */
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
   * Parses a JSON message and submits it to registered handlers.
   *
   * @param jsonStr the JSON string representing the message to parse and dispatch
   */
  public static void parseAndDispatch(String jsonStr,
                                      Map<String, List<Consumer<Object>>> handlers,
                                      Map<Consumer<Object>, ExecutorService> handlerExecutors) {
    RemoteMessage result = RemoteUtils.parseMessage(jsonStr);
    List<Consumer<Object>> list = handlers.get(result.address());
    if (list != null) {
      for (Consumer<Object> handler : list) {
        handlerExecutors.computeIfAbsent(handler, h ->
          Executors.newSingleThreadExecutor(r -> new Thread(r, "Handler-" + h.hashCode()))
        ).submit(() -> handler.accept(result.payload()));
      }
    }

  }
}
