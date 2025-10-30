package com.safety_box.orchestrator;

import com.safety_box.core.*;
import org.json.JSONObject;


public class Main {

  public static void main(String[] args) {
    // start all configured device protocol handlers
    JSONObject servicesConfigs;
    JSONObject communicationConfig;
    try {
      servicesConfigs = ConfigLoader.loadConfig("services.json");
      ConfigLoader.validateServiceConfigs(servicesConfigs);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    try{
      communicationConfig = ConfigLoader.loadConfig("communication.json");
      ConfigLoader.validateCommunicationConfigs(communicationConfig);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    Transport transport;

    if (communicationConfig.getString("type").equals("TCP")){
      transport = new TCPTransport(4999);
    } else if (communicationConfig.getString("type").equals("UDP")){
      transport = new UDPTransport(4999);
    } else {
      throw new RuntimeException("Invalid communication type config");
    }
    SocketEventBus eventBus = new SocketEventBus(transport);
    if (communicationConfig.has("peers")){
      for (Object peer : communicationConfig.getJSONArray("peers")) {
        JSONObject peerConfig = (JSONObject) peer;
        eventBus.addPeer(new Peer(peerConfig.getString("host"), peerConfig.getInt("port")));
      }
    }


    Manager manager = new Manager(servicesConfigs, eventBus);
    for (String key : servicesConfigs.keySet()) {
      manager.instantiate(key);
    }
  }
}

