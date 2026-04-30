package com.framed.streamer.dispatcher;

import com.framed.core.EventBus;
import com.framed.core.Service;
import com.framed.streamer.model.DataPoint;
import com.framed.streamer.Parser;
import org.json.JSONArray;
import org.json.JSONObject;


import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class Dispatcher extends Service {
  private final Set<String> addresses = ConcurrentHashMap.newKeySet();

  public Dispatcher(EventBus eventBus, JSONArray devices) {
    super(eventBus);

    for (Object deviceObj : devices) {
      final String deviceID = deviceObj.toString();

      eventBus.register("%s.addresses".formatted(deviceID), msg -> {
        final String address = msg.toString();

        // atomic "register once"
        if (addresses.add(address)) {
          eventBus.register(address, msg_ -> {
            try {
              if (!(msg_ instanceof JSONObject body)) return;

              // defensive copy - don't mutate shared JSON
              JSONObject enriched = new JSONObject(body.toString());
              enriched.put("deviceID", deviceID);

              DataPoint<?> dp = Parser.parse(enriched);

              // retry loop to avoid loss on transient IO errors
              pushWithRetry(dp);

            } catch (Exception e) {
              // DO NOT throw out of handler; log and continue
              // (otherwise you risk losing future events depending on bus/transport)
              System.err.println("Dispatcher handler failed: " + e.getMessage());
              e.printStackTrace();
            }
          });
        }
      });
    }
  }

  private void pushWithRetry(DataPoint<?> dp) throws InterruptedException {
    long backoffMs = 100;
    while (true) {
      try {
        push(dp);
        return;
      } catch (IOException ioe) {
        Thread.sleep(backoffMs);
        backoffMs = Math.min(backoffMs * 2, 5000);
      }
    }
  }

  public abstract void push(DataPoint<?> dataPoint) throws IOException;
  public abstract void pushBatch(List<DataPoint<?>> batch);
}

