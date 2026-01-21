package com.framed.cdss.casestudy;

import com.framed.cdss.Actor;
import com.framed.cdss.LimitClassifier;
import com.framed.core.EventBus;
import kotlin.Pair;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public class EtCO2Classifier extends LimitClassifier {
  final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
  private static final String ETCO2_ADDRESS = "Oxylog-3000-Plus-00.End-tidal CO2 concentration, etCO2.parsed";
  static final Map<String, Pair<Integer, Integer>> ETCO2_LIMITS = Map.of(ETCO2_ADDRESS, new Pair<>(1, 2));
  /**
   * Constructs a {@code LimitClassifier}.
   *
   * @param eventBus       the event bus
   * @throws IllegalArgumentException if limited channels are not a subset of input channels
   */
  protected EtCO2Classifier(EventBus eventBus) {
    super(eventBus,
      List.of(
        Map.of(
          ETCO2_ADDRESS, "*"
        )
      )
      ,
      List.of(
        ETCO2_ADDRESS
      ),
      List.of(
        "etCO2_limit"
      ),
      ETCO2_LIMITS
    );
  }

  @Override
  public void fireFunction(Map<String, Object> latestSnapshot) {
    Map<String, Integer> alarmStates = checkLimits(latestSnapshot);
    for (String ch: getInputChannels()) {
      JSONObject result = new JSONObject();
      result.put("timestamp", LocalDateTime.now().format(formatter));
      result.put("value", alarmStates.get(ch));
      result.put("channelID", "S/F");
      result.put("className", "SFActor");
      eventBus.publish("S/F", result);
      System.out.println(alarmStates.get(ch));
    }
  }
}
