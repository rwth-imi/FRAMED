package com.framed.communicator.driver.protocol.medibus;

import com.fazecast.jSerialComm.*;
import com.framed.communicator.driver.protocol.Protocol;
import com.framed.communicator.driver.protocol.medibus.utils.DataConstants;
import com.framed.communicator.driver.protocol.medibus.utils.MedibusState;
import com.framed.core.EventBus;
import com.framed.core.utils.Timer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    String deviceID,
    String portName,
    int baudRate,
    int dataBits,
    int stopBits,
    int waveFormType,
    boolean realTime,
    boolean slowData,
    String multiplier,
    EventBus eventBus) {
    super(deviceID, eventBus);
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
      logger.warning("Failed to open serial port: " + portName + " with message: " +  e);
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
    logger.fine("Sending command: poll_request_stop_com");
    timer.shutdown();
    sendCommand(DataConstants.poll_request_stop_com);
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
    eventBus.publish(id, data);
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
      logger.warning("Received response too short: " + response);
      return;
    }

    writeData(packetBuffer);
    String echo = response.substring(0, 2);
    logger.fine("Handling response with echo: " + stringToHex(echo) + " in state " + currentState);

    switch (currentState) {
      case INITIALIZING -> {
        switch (echo) {
          case DataConstants.ICC_COMMAND -> {
            logger.fine("ICC command received.");
            commandEchoResponse(DataConstants.poll_request_icc_msg);
            logger.fine("Sending command: poll_request_deviceid");
            sendCommand(DataConstants.poll_request_deviceid);
            currentState = MedibusState.IDENTIFYING;
          }
          case DataConstants.ICC_RESPONSE -> {
            logger.fine("ICC response received. Transitioning to IDENTIFYING.");
            logger.fine("Sending command: poll_request_deviceid");
            sendCommand(DataConstants.poll_request_deviceid);
            currentState = MedibusState.IDENTIFYING;
          }
          default -> {
            String echoHex = stringToHex(echo);
            logger.warning("Received unknown response from ICC: " + echoHex);
          }
        }
      }
      case IDENTIFYING -> {
        switch (echo) {
          case DataConstants.DEV_ID_REQUEST -> {
            logger.fine("Device ID request received. Sending Device ID.");
            sendDeviceID();
          }
          case DataConstants.NOP_REQUEST -> {
            logger.fine("NOP request received.");
            logger.fine("Sending command: poll_request_deviceid");
            sendCommand(DataConstants.poll_request_deviceid);
          }
          case DataConstants.DEV_ID_RESPONSE -> {
            logger.fine("Device ID response received.");
            if (realTime) {
              logger.fine("Realtime enabled. Transitioning to CONFIGURING.");
              currentState = MedibusState.CONFIGURING;
              timer.setTimer(200, () -> sendCommand(DataConstants.poll_request_real_time_data_config));
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
          case DataConstants.ICC_COMMAND -> {
            logger.fine("ICC command received. Returning to INITIALIZING.");
            currentState = MedibusState.INITIALIZING;
            commandEchoResponse(DataConstants.poll_request_icc_msg);
          }
          case DataConstants.NOP_REQUEST -> {
            logger.fine("NOP request received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
          case DataConstants.RT_CONFIG_RESPONSE -> {
            logger.fine("Realtime config received. Sending transmission config.");
            logger.fine("Sending command: poll_configure_real_time_transmission");
            readRealtimeConfigResponse(packetBuffer, eventBus, id);
            configureRealtimeTransmission();
          }
          case DataConstants.RT_TRANSMISSION_RESPONSE -> {
            logger.fine("Realtime transmission configured. Transitioning to ACTIVE.");
            currentState = MedibusState.ACTIVE;
            logger.fine("Sending Sync-Command to enable datastreams.");
            setConfiguredDataStreams(false);
            transitToActive();
          }
          default -> {
            String echoHex = stringToHex(echo);
            logger.warning("Unknown response in CONFIGURING: " + echoHex);
          }
        }
      }

      case ACTIVE -> {
        switch (echo) {
          case DataConstants.NOP_RESPONSE -> {
            logger.fine("NOP response received.");
            if (this.slowData) {
              logger.fine("Sending command: poll_request_config_measured_data_codepage1");
              sendCommand(DataConstants.poll_request_config_measured_data_codepage1);
            }
          }
          case DataConstants.NOP_REQUEST -> {
            logger.fine("NOP request received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
            if (this.slowData) {
              logger.fine("Sending command: poll_request_config_measured_data_codepage1");
              sendCommand(DataConstants.poll_request_config_measured_data_codepage1);
            }
          }
          case DataConstants.RT_CONFIG_CHANGED -> {
            logger.fine("Realtime config changed. Reconfiguring.");
            setConfiguredDataStreams(true);
            currentState = MedibusState.CONFIGURING;
            logger.fine("Sending command: poll_request_real_time_data_config");
            sendCommand(DataConstants.poll_request_real_time_data_config);
          }
          case DataConstants.ICC_COMMAND -> {
            logger.fine("ICC command received. Returning to INITIALIZING.");
            currentState = MedibusState.INITIALIZING;
            commandEchoResponse(DataConstants.poll_request_icc_msg);
          }
          case DataConstants.DATA_RESPONSE_CP1 -> { // Data response cp1
            logger.log(Level.INFO, "Received: Data CP1 response");
            logger.fine("Sending command: poll_request_config_measured_data_codepage2");
            sendCommand(DataConstants.poll_request_config_measured_data_codepage2);
          }
          case DataConstants.DATA_RESPONSE_CP2 -> { // Data response cp2
            logger.log(Level.INFO, "Received: Data CP2 response");
            logger.fine("Sending command: poll_request_device_settings");
            sendCommand(DataConstants.poll_request_device_settings);
          }
          case DataConstants.SETTINGS_RESPONSE -> { // Data response device settings
            logger.log(Level.INFO, "Received: Data device settings response");
            logger.fine("Sending command: poll_request_text_messages");
            sendCommand(DataConstants.poll_request_text_messages);
          }
          case DataConstants.TEXT_RESPONSE -> { // Data response text messages
            logger.log(Level.INFO, "Received: Data text messages response");
            logger.fine("Sending command: poll_request_config_alarms_codepage1");
            sendCommand(DataConstants.poll_request_config_alarms_codepage1);
          }
          case DataConstants.ALARM_RESPONSE_CP1 -> { // Alarm response cp1
            logger.log(Level.INFO, "Received: Alarm CP1 response");
            logger.fine("Sending command: poll_request_config_alarms_codepage2");
            sendCommand(DataConstants.poll_request_config_alarms_codepage2);
          }
          case DataConstants.ALARM_RESPONSE_CP2 -> { // Alarm response cp2
            logger.log(Level.INFO, "Received: Alarm CP2 response");
            logger.fine("Sending command: poll_request_config_measured_data_codepage1");
            sendCommand(DataConstants.poll_request_config_measured_data_codepage1);
          }
          default -> {
            String echoHex = stringToHex(echo);
            logger.warning("Unknown response in ACTIVE: " + echoHex);
            if (echo.startsWith("\u001b")) {
              byte[] echoResponse = echo.substring(1).getBytes(StandardCharsets.US_ASCII);
              commandEchoResponse(echoResponse);
            }
          }
        }
      }

      default -> {
        switch (echo) {
          case DataConstants.NOP_RESPONSE -> {
            logger.fine("NOP response received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
          case DataConstants.NOP_REQUEST -> {
            logger.fine("NOP request received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
          default -> logger.warning("Unhandled state or echo: " + currentState + " / " + stringToHex(echo));
        }
      }
    }
  }

  private void transitToActive() {
    if (this.slowData) {
      logger.fine("Slow Data transmission configured.");
      logger.fine("Sending command: poll_request_config_measured_data_codepage1");
      sendCommand(DataConstants.poll_request_config_measured_data_codepage1);
    } else {
      logger.fine("Slow Data transmission not configured.");
      logger.fine("Keeping connection alive by NOP");
      timer.setPeriodic(2000, () -> sendCommand(DataConstants.poll_request_no_operation));
    }
  }

  private void sendICC() {
    logger.fine("Sending command: poll_request_icc_msg");
    sendCommand(DataConstants.poll_request_icc_msg); // ICC
    currentState = MedibusState.INITIALIZING;
  }

  public void configureRealtimeTransmission() {
    if (this.waveFormType == 0) return; // config set to "No waveform data"
    ArrayList<Byte> tempTxBuffList = new ArrayList<>();
    ArrayList<Byte> waveFormTypeList = createWaveFormTypeList(this.waveFormType);

    byte[] finalBuffer = getRealtimeConfigMessage(waveFormTypeList, tempTxBuffList, multiplier);

    logger.log(Level.INFO, "Send: Configure realtime transmission (command)");
    sendCommand(finalBuffer);
  }



  private void sendCommand(byte[] commandBytes) {
    if (commandBytes.length == 0) {
      return;
    }

    byte[] inputBuffer = new byte[commandBytes.length + 1];
    inputBuffer[0] = DataConstants.BOFCOMCHAR;
    System.arraycopy(commandBytes, 0, inputBuffer, 1, commandBytes.length);

    byte computedChecksum = computeChecksum(inputBuffer);
    byte[] checksumBytes = String.format("%02X", computedChecksum).getBytes(StandardCharsets.US_ASCII);
    byte[] finalMessage = concatBuffer(inputBuffer, checksumBytes);

    serialPort.writeBytes(finalMessage, finalMessage.length, 0);

  }

  public void commandEchoResponse(byte[] commandBuffer) {
    byte[] inputBuffer = new byte[commandBuffer.length + 2]; // +2 for BOF and checksum
    System.arraycopy(commandBuffer, 0, inputBuffer, 1, commandBuffer.length);
    inputBuffer[0] = DataConstants.BOFRESPCHAR;

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
    setDataStreams(DataConstants.SC_DATASTREAM_1_4, disable);
    if (this.waveFormType == 4) {
      setDataStreams(DataConstants.SC_DATASTREAM_5_8, disable);
      setDataStreams(DataConstants.SC_DATASTREAM_9_12, disable);
    }
  }

  public void setDataStreams(byte syncCommand, boolean disable) {
    // enable or disable data streams
    byte[] finalBuffer = getDataStreamConfigBuffer(syncCommand, disable);
    this.serialPort.writeBytes(finalBuffer, finalBuffer.length, 0);
  }
}
