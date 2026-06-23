package com.framed.communicator.driver.protocol.medibus;

import com.fazecast.jSerialComm.*;
import com.framed.io.protocol.Protocol;
import com.framed.communicator.driver.protocol.medibus.utils.ProtocolMap;
import com.framed.communicator.driver.protocol.medibus.utils.MedibusState;
import com.framed.core.EventBus;
import com.framed.core.utils.Timer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import static com.framed.communicator.driver.protocol.medibus.utils.DataUtils.*;
import static com.framed.communicator.driver.protocol.medibus.utils.ParsingUtils.readRealtimeConfigResponse;
import static com.framed.communicator.driver.protocol.medibus.utils.ParsingUtils.stringToHex;

public class MedibusProtocol extends Protocol {

  private boolean slowData;

  private MedibusState currentState = MedibusState.IDLE;

  int waveFormType;
  private final boolean realTime;
  private final String multiplier;
  private SerialPort serialPort;
  private final MedibusFramer framer;
  private final Timer timer = new Timer();


  public MedibusProtocol(
    String id,
    String portName,
    int baudRate,
    int dataBits,
    int stopBits,
    int waveFormType,
    boolean realTime,
    boolean slowData,
    String multiplier,
    EventBus eventBus) {
    super(id, eventBus);
    // initialize globals from config
    this.realTime = realTime;
    this.waveFormType = waveFormType;
    this.slowData = slowData;
    this.multiplier = multiplier;

    // initialize serial port
    try {
      this.serialPort = SerialPort.getCommPort(portName);
      serialPort.setBaudRate(baudRate);
      serialPort.setParity(SerialPort.EVEN_PARITY);
      serialPort.setNumDataBits(dataBits);
      serialPort.setNumStopBits(stopBits);
      serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);
      serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
      serialPort.setRTS();
      serialPort.setDTR();
    } catch (Exception e) {
      String errorMsg = "Failed to open serial port: %s with message: %s".formatted(portName, e);
      logger.log(Level.WARNING, errorMsg);
    }

    this.framer = new MedibusFramer(this::handleResponse, eventBus, this.id);
    logger.fine("Trying to initialize communication...");

    // connect to Medibus.X device
    if (serialPort.openPort()) {
      connect();

    }
  }

  @Override
  public void connect() {
    listenToSerial();
    sendICC();
  }

  @Override
  public void stop() {
    currentState = MedibusState.TERMINATING;
    logger.fine("Sending command: POLL_REQUEST_STOP_COM");
    timer.shutdown();
    sendCommand(ProtocolMap.POLL_REQUEST_STOP_COM);
  }


  private void readData() {
    byte[] buffer = new byte[serialPort.bytesAvailable()];
    int numRead = serialPort.readBytes(buffer, buffer.length);
    if (numRead > 0) {
      for (int i = 0; i < numRead; i++) {
        framer.createFrameListFromByte(buffer[i]);
      }
    }
  }

  private void writeData(byte[] data) {
    eventBus.publish(id,data);
  }

  private void listenToSerial() {
    serialPort.addDataListener(new SerialPortDataListener() {
      @Override
      public int getListeningEvents() {
        return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
      }

      @Override
      public void serialEvent(SerialPortEvent event) {
        if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) return;

        readData();
      }
    });
  }

  private void handleResponse(byte[] packetBuffer) {
    String response = new String(packetBuffer, StandardCharsets.US_ASCII);
    if (response.length() < 2) {
      logger.log(Level.WARNING, "Received response too short: {}", response);
      return;
    }

    writeData(packetBuffer);
    String echo = response.substring(0, 2);
    String logMsg = "Handling response with echo: %s in state %s".formatted(stringToHex(echo), currentState);
    logger.fine(logMsg);

    switch (currentState) {
      case INITIALIZING -> {
        switch (echo) {
          case ProtocolMap.ICC_COMMAND -> {
            logger.fine("ICC command received.");
            commandEchoResponse(ProtocolMap.POLL_REQUEST_ICC);
            logger.fine("Sending command: POLL_REQUEST_DEVICE_ID");
            sendCommand(ProtocolMap.POLL_REQUEST_DEVICE_ID);
            currentState = MedibusState.IDENTIFYING;
          }
          case ProtocolMap.ICC_RESPONSE -> {
            logger.fine("ICC response received. Transitioning to IDENTIFYING.");
            logger.fine("Sending command: POLL_REQUEST_DEVICE_ID");
            sendCommand(ProtocolMap.POLL_REQUEST_DEVICE_ID);
            currentState = MedibusState.IDENTIFYING;
          }
          default -> {
            String echoHex = stringToHex(echo);
            logger.log(Level.WARNING, "Received unknown response from ICC: {}", echoHex);
          }
        }
      }
      case IDENTIFYING -> {
        switch (echo) {
          case ProtocolMap.DEV_ID_REQUEST -> {
            logger.fine("Device ID request received. Sending Device ID.");
            sendDeviceID();
          }
          case ProtocolMap.NOP_REQUEST -> {
            logger.fine("NOP request received.");
            logger.fine("Sending command: POLL_REQUEST_DEVICE_ID");
            sendCommand(ProtocolMap.POLL_REQUEST_DEVICE_ID);
          }
          case ProtocolMap.DEV_ID_RESPONSE -> {
            logger.fine("Device ID response received.");
            if (realTime) {
              logger.fine("Realtime enabled. Transitioning to CONFIGURING.");
              currentState = MedibusState.CONFIGURING;
              timer.setTimer(200, () -> sendCommand(ProtocolMap.POLL_REQUEST_RT_DATA_CONFIG));
            } else {
              logger.fine("Transitioning to ACTIVE.");
              currentState = MedibusState.ACTIVE;
              transitToActive();
            }
          }
          default -> {
            if (echo.startsWith("\u001b")) {
              byte[] echoResponse = echo.substring(1).getBytes(StandardCharsets.US_ASCII);
              commandEchoResponse(echoResponse);
            }
          }
        }
      }

      case CONFIGURING -> {
        switch (echo) {
          case ProtocolMap.ICC_COMMAND -> {
            logger.fine("ICC command received. Returning to INITIALIZING.");
            currentState = MedibusState.INITIALIZING;
            commandEchoResponse(ProtocolMap.POLL_REQUEST_ICC);
          }
          case ProtocolMap.NOP_REQUEST -> {
            logger.fine("NOP request received.");
            commandEchoResponse(ProtocolMap.POLL_REQUEST_NOP);
          }
          case ProtocolMap.RT_CONFIG_RESPONSE -> {
            logger.fine("Realtime config received. Sending transmission config.");
            logger.fine("Sending command: POLL_CONFIGURE_RT_TRANSMISSION");
            readRealtimeConfigResponse(packetBuffer, eventBus, id);
            configureRealtimeTransmission();
          }
          case ProtocolMap.RT_TRANSMISSION_RESPONSE -> {
            logger.fine("Realtime transmission configured. Transitioning to ACTIVE.");
            currentState = MedibusState.ACTIVE;
            logger.fine("Sending Sync-Command to enable datastreams.");
            setConfiguredDataStreams(false);
            transitToActive();
          }
          default -> {
            String echoHex = stringToHex(echo);
            logger.log(Level.WARNING, "Unknown response in CONFIGURING: {}", echoHex);
          }
        }
      }

      case ACTIVE -> {
        switch (echo) {
          case ProtocolMap.NOP_RESPONSE -> {
            logger.fine("NOP response received.");
            if (this.slowData) {
              logger.fine("Sending command: POLL_REQUEST_MEASURED_DATA_CP1");
              sendCommand(ProtocolMap.POLL_REQUEST_MEASURED_DATA_CP1);
            }
          }
          case ProtocolMap.NOP_REQUEST -> {
            logger.fine("NOP request received.");
            commandEchoResponse(ProtocolMap.POLL_REQUEST_NOP);
            if (this.slowData) {
              logger.fine("Sending command: POLL_REQUEST_MEASURED_DATA_CP1");
              sendCommand(ProtocolMap.POLL_REQUEST_MEASURED_DATA_CP1);
            }
          }
          case ProtocolMap.RT_CONFIG_CHANGED -> {
            logger.fine("Realtime config changed. Reconfiguring.");
            setConfiguredDataStreams(true);
            currentState = MedibusState.CONFIGURING;
            logger.fine("Sending command: POLL_REQUEST_RT_DATA_CONFIG");
            sendCommand(ProtocolMap.POLL_REQUEST_RT_DATA_CONFIG);
          }
          case ProtocolMap.ICC_COMMAND -> {
            logger.fine("ICC command received. Returning to INITIALIZING.");
            currentState = MedibusState.INITIALIZING;
            commandEchoResponse(ProtocolMap.POLL_REQUEST_ICC);
          }
          case ProtocolMap.DATA_RESPONSE_CP1 -> { // Data response cp1
            logger.fine("Received: Data CP1 response");
            logger.fine("Sending command: POLL_REQUEST_MEASURED_DATA_CP2");
            sendCommand(ProtocolMap.POLL_REQUEST_MEASURED_DATA_CP2);
          }
          case ProtocolMap.DATA_RESPONSE_CP2 -> { // Data response cp2
            logger.fine("Received: Data CP2 response");
            logger.fine("Sending command: POLL_REQUEST_DEVICE_SETTINGS");
            sendCommand(ProtocolMap.POLL_REQUEST_DEVICE_SETTINGS);
          }
          case ProtocolMap.SETTINGS_RESPONSE -> { // Data response device settings
            logger.fine("Received: Data device settings response");
            logger.fine("Sending command: POLL_REQUEST_TEXT_MESSAGES");
            sendCommand(ProtocolMap.POLL_REQUEST_TEXT_MESSAGES);
          }
          case ProtocolMap.TEXT_RESPONSE -> { // Data response text messages
            logger.fine("Received: Data text messages response");
            logger.fine("Sending command: POLL_REQUEST_ALARMS_CP1");
            sendCommand(ProtocolMap.POLL_REQUEST_ALARMS_CP1);
          }
          case ProtocolMap.ALARM_RESPONSE_CP1 -> { // Alarm response cp1
            logger.fine("Received: Alarm CP1 response");
            logger.fine("Sending command: POLL_REQUEST_ALARMS_CP2");
            sendCommand(ProtocolMap.POLL_REQUEST_ALARMS_CP2);
          }
          case ProtocolMap.ALARM_RESPONSE_CP2 -> { // Alarm response cp2
            logger.fine("Received: Alarm CP2 response");
            logger.fine("Sending command: POLL_REQUEST_MEASURED_DATA_CP1");
            sendCommand(ProtocolMap.POLL_REQUEST_MEASURED_DATA_CP1);
          }
          default -> {
            String echoHex = stringToHex(echo);
            logger.log(Level.WARNING, "Unknown response in ACTIVE: {}", echoHex);
            if (echo.startsWith("\u001b")) {
              byte[] echoResponse = echo.substring(1).getBytes(StandardCharsets.US_ASCII);
              commandEchoResponse(echoResponse);
            }
          }
        }
      }

      default -> {
        switch (echo) {
          case ProtocolMap.NOP_RESPONSE -> {
            logger.fine("NOP response received.");
            commandEchoResponse(ProtocolMap.POLL_REQUEST_NOP);
          }
          case ProtocolMap.NOP_REQUEST -> {
            logger.fine("NOP request received.");
            commandEchoResponse(ProtocolMap.POLL_REQUEST_NOP);
          }

          default -> {
            logMsg = "Unhandled state or echo: %s / %s".formatted(currentState, stringToHex(echo));
            logger.log(Level.WARNING, logMsg);
          }
        }
      }
    }
  }

  private void transitToActive() {
    if (this.slowData) {
      logger.fine("Slow Data transmission configured.");
      logger.fine("Sending command: POLL_REQUEST_MEASURED_DATA_CP1");
      sendCommand(ProtocolMap.POLL_REQUEST_MEASURED_DATA_CP1);
    } else {
      logger.fine("Slow Data transmission not configured.");
      logger.fine("Keeping connection alive by NOP");
      timer.setPeriodic(2000, () -> sendCommand(ProtocolMap.POLL_REQUEST_NOP));
    }
  }

  private void sendICC() {
    logger.fine("Sending command: POLL_REQUEST_ICC");
    sendCommand(ProtocolMap.POLL_REQUEST_ICC); // ICC
    currentState = MedibusState.INITIALIZING;
  }

  public void configureRealtimeTransmission() {
    if (this.waveFormType == 0) return; // config set to "No waveform data"
    List<Byte> tempTxBuffList = new ArrayList<>();
    List<Byte> waveFormTypeList = createWaveFormTypeList(this.waveFormType);

    byte[] finalBuffer = getRealtimeConfigMessage(waveFormTypeList, tempTxBuffList, multiplier);

    logger.fine("Send: Configure realtime transmission (command)");
    sendCommand(finalBuffer);
  }



  private void sendCommand(byte[] commandBytes) {
    if (commandBytes.length == 0) {
      return;
    }

    byte[] inputBuffer = new byte[commandBytes.length + 1];
    inputBuffer[0] = ProtocolMap.BOFCOMCHAR;
    System.arraycopy(commandBytes, 0, inputBuffer, 1, commandBytes.length);

    byte computedChecksum = computeChecksum(inputBuffer);
    byte[] checksumBytes = String.format("%02X", computedChecksum).getBytes(StandardCharsets.US_ASCII);
    byte[] finalMessage = concatBuffer(inputBuffer, checksumBytes);

    serialPort.writeBytes(finalMessage, finalMessage.length, 0);

  }

  public void commandEchoResponse(byte[] commandBuffer) {
    byte[] inputBuffer = new byte[commandBuffer.length + 2]; // +2 for BOF and checksum
    System.arraycopy(commandBuffer, 0, inputBuffer, 1, commandBuffer.length);
    inputBuffer[0] = ProtocolMap.BOFRESPCHAR;

    byte checksumComputed = computeChecksum(inputBuffer);

    String checksumToAsciiHex = String.format("%02x", checksumComputed).toUpperCase();
    byte[] checksumAsciiHexBytes = checksumToAsciiHex.getBytes(StandardCharsets.US_ASCII);

    byte[] finalTxBuff = concatBuffer(inputBuffer, checksumAsciiHexBytes);

    this.serialPort.writeBytes(finalTxBuff, finalTxBuff.length, 0);

  }

  public void sendDeviceID() {
    byte[] txBuffer = createDevIDMessage();
    commandEchoResponse(txBuffer);
  }

  public void setConfiguredDataStreams(boolean disable) {
    if (this.waveFormType == 0) return;
    setDataStreams(ProtocolMap.SC_DATASTREAM_1_4, disable);
    if (this.waveFormType == 4) {
      setDataStreams(ProtocolMap.SC_DATASTREAM_5_8, disable);
      setDataStreams(ProtocolMap.SC_DATASTREAM_9_12, disable);
    }
  }

  public void setDataStreams(byte syncCommand, boolean disable) {
    // enable or disable data streams
    byte[] finalBuffer = getDataStreamConfigBuffer(syncCommand, disable);
    this.serialPort.writeBytes(finalBuffer, finalBuffer.length, 0);
  }
}
