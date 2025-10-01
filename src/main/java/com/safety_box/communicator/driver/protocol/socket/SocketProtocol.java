package com.safety_box.communicator.driver.protocol.socket;

import com.safety_box.communicator.driver.protocol.Protocol;
import com.safety_box.core.EventBus;
import com.safety_box.core.EventBusInterface;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class SocketProtocol extends Protocol {
  Socket socket;
  int port;

  public SocketProtocol(String id, EventBusInterface eventBus, int port) {
    super(id,  eventBus);
    this.port = port;
    connect();
  }

  @Override
  public void connect() {
    try (ServerSocket serverSocket = new ServerSocket(port)) {
      System.out.println("Server is listening on port " + port);
      while (true) {
        // Wait for a client to connect
        Socket socket = serverSocket.accept();
        System.out.println("New client connected");


        InputStream input = socket.getInputStream();
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
          String message = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
          System.out.println(id + " Received: " + message);
          eventBus.publish(id, message);
        }



        // Close the socket
        socket.close();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


  @Override
  public void stop() {
    if (socket == null) {
      return;
    }
    try {
      if (!socket.isClosed()) {
        socket.close();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }



}
