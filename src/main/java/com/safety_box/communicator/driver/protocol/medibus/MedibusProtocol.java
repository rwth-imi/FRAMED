package com.safety_box.communicator.driver.protocol.medibus;

import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.safety_box.communicator.driver.parser.medibus.MedibusFrameParser;
import com.safety_box.communicator.driver.protocol.Protocol;
import com.fazecast.jSerialComm.SerialPort;
import com.safety_box.communicator.driver.utils.DataUtils;
import com.safety_box.communicator.driver.utils.DataConstants;
import io.vertx.core.*;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MedibusProtocol extends Protocol<Byte> {

  private long nopTimerID;

  private enum State {
    IDLE, INITIALIZING, IDENTIFYING, CONFIGURING, ACTIVE, REALTIME, TERMINATING

  }
  private State currentState = State.IDLE;

  private String portName;
  private int baudRate;
  private int dataBits;
  private int stopBits;
  private int bufferSize;
  private int waveFormType;
  private boolean realTime;
  private SerialPort serialPort;
  private MedibusFrameParser frameParser;

  private Vertx vertx;
  private JsonObject config;

  private static final Logger logger = Logger.getLogger(MedibusProtocol.class.getName());


  @Override
  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
    this.vertx = vertx;
    this.config = context.config();
    this.deviceID = config.getString("deviceID");
    this.portName = config.getString("portName");
    this.baudRate = config.getInteger("baudRate");
    this.dataBits = config.getInteger("dataBits");
    this.bufferSize = config.getInteger("bufferSize");
    this.realTime = config.getBoolean("realTime");
    this.waveFormType = config.getInteger("waveFormType");
  }

  @Override
  public Future<?> start() throws Exception {
    this.serialPort = SerialPort.getCommPort(portName);
    serialPort.setBaudRate(baudRate);
    serialPort.setParity(SerialPort.EVEN_PARITY);
    serialPort.setNumDataBits(dataBits);
    serialPort.setNumStopBits(stopBits);
    serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);
    serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
    serialPort.setRTS();
    serialPort.setDTR();


    this.frameParser = new MedibusFrameParser(this::handleResponse);
    logger.info("Trying to initialize communication...");

    if (serialPort.openPort()) {
      connect();

    }
    return super.start();
  }

  @Override
  public Future<?> stop() throws Exception {
    disconnect();
    return super.stop();
  }

  private void listenToSerial() {
    this.frameParser = new MedibusFrameParser(this::handleResponse);

    serialPort.addDataListener(new SerialPortDataListener() {
      @Override
      public int getListeningEvents() {
        return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
      }

      @Override
      public void serialEvent(SerialPortEvent event) {
        if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) return;

        byte[] buffer = new byte[serialPort.bytesAvailable()];
        int numRead = serialPort.readBytes(buffer, buffer.length);
        if (numRead > 0) {
          for (int i = 0; i < numRead; i++) {
            frameParser.createFrameListFromByte(buffer[i]);
          }
        }
      }
    });
  }

  private void handleResponse(String response) {
    if (response.length() < 2) {
      logger.warning("Received response too short: " + response);
      return;
    }

    String echo = response.substring(0, 2);
    logger.info("Handling response with echo: " + DataUtils.stringToHex(echo) + " in state " + currentState);

    switch (currentState) {
      case INITIALIZING -> {
        switch (echo) {
          case "\u001bQ" -> {
            logger.info("ICC command received.");
            commandEchoResponse(DataConstants.poll_request_icc_msg);
            sendCommand(DataConstants.poll_request_deviceid);
            currentState = State.IDENTIFYING;
          }
          case "\u0001Q" -> {
            logger.info("ICC response received. Transitioning to IDENTIFYING.");
            sendCommand(DataConstants.poll_request_deviceid);
            currentState = State.IDENTIFYING;
          }
        }
      }
      case IDENTIFYING -> {
        switch (echo) {
          case "\u001bR" -> {
            logger.info("Device ID request received. Sending Device ID.");
            sendDeviceID();
          }
          case "\u001b\u0030" -> {
            logger.info("NOP request received.");
            sendCommand(DataConstants.poll_request_deviceid);
          }
          case "\u0001R" -> {
            logger.info("Device ID response received.");
            if (realTime) {
              logger.info("Realtime enabled. Transitioning to CONFIGURING.");
              currentState = State.CONFIGURING;
              vertx.setTimer(200, id -> {
                sendCommand(DataConstants.poll_request_real_time_data_config);
              });
            } else {
              logger.info("Transitioning to ACTIVE.");
              currentState = State.ACTIVE;
              //nopTimerID = vertx.setPeriodic(2000, id -> sendCommand(DataConstants.poll_request_no_operation));
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
          case "\u001b\u0030" -> {
            logger.info("NOP request received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
          case "\u0001S" -> {
            logger.info("Realtime config received. Sending transmission config.");
            sendCommand(DataConstants.poll_configure_real_time_transmission);
          }
          case "\u0001T" -> {
            logger.info("Realtime transmission configured. Transitioning to ACTIVE.");
            setConfiguredDataStreams(false);
            currentState = State.ACTIVE;
            sendCommand(DataConstants.poll_request_config_measured_data_codepage1);
            //nopTimerID = vertx.setPeriodic(2000, id -> sendCommand(DataConstants.poll_request_no_operation));
          }
          default -> {
            logger.warning("Unknown response in CONFIGURING: " + DataUtils.stringToHex(echo));
          }
        }
      }

      case ACTIVE -> {
        switch (echo) {
          case "\u0001\u0030" -> {
            logger.info("NOP response received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
          case "\u001b\u0030" -> {
            logger.info("NOP request received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
          case "\u001bV" -> {
            logger.info("Realtime config changed. Reconfiguring.");
            setConfiguredDataStreams(true);
            currentState = State.CONFIGURING;
            vertx.setTimer(200, id -> {
              sendCommand(DataConstants.poll_request_real_time_data_config);
            });
          }
          case "\u001bQ" -> {
            logger.info("ICC command received. Returning to INITIALIZING.");
            currentState = State.INITIALIZING;
            commandEchoResponse(DataConstants.poll_request_icc_msg);
          }
          case "\u0001$" -> { // Data response cp1
            logger.log(Level.FINE, "Received: Data CP1 response");
            sendCommand(DataConstants.poll_request_config_measured_data_codepage2);}
          case "\u0001+" -> { // Data response cp2
            logger.log(Level.FINE, "Received: Data CP2 response");
            sendCommand(DataConstants.poll_request_device_settings);
          }
          case "\u0001)" -> { // Data response device settings
            logger.log(Level.FINE, "Received: Data device settings response");
            sendCommand(DataConstants.poll_request_text_messages);
          }
          case "\u0001*" -> { // Data response text messages
            logger.log(Level.FINE, "Received: Data text messages response");
            sendCommand(DataConstants.poll_request_config_alarms_codepage1);
          }
          case "\u0001'" -> { // Alarm response cp1
            logger.log(Level.FINE, "Received: Alarm CP1 response");
            sendCommand(DataConstants.poll_request_config_alarms_codepage2);
          }
          case "\u0001." -> { // Alarm response cp2
            logger.log(Level.FINE, "Received: Alarm CP2 response");
            sendCommand(DataConstants.poll_request_config_measured_data_codepage1);
          }
          default -> {
            logger.warning("Unknown response in ACTIVE: " + DataUtils.stringToHex(echo));
            if (echo.startsWith("\u001b")) {
              byte[] echoResponse = echo.substring(1).getBytes(StandardCharsets.US_ASCII);
              commandEchoResponse(echoResponse);
            }
          }
        }
      }

      default -> {
        switch (echo) {
          case "\u0001\u0030" -> {
            logger.info("NOP response received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
          case "\u001b\u0030" -> {
            logger.info("NOP request received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
          default -> {
            logger.warning("Unhandled state or echo: " + currentState + " / " + DataUtils.stringToHex(echo));
          }
        }
      }
    }
  }

  private void sendICC() {
    sendCommand(DataConstants.poll_request_icc_msg); // ICC
    currentState = State.INITIALIZING;
  }

  @Override
  public void connect() {
    listenToSerial();
    sendICC();
  }

  @Override
  public void disconnect() {
    currentState = State.TERMINATING;
    sendCommand(DataConstants.poll_request_stop_com);
  }

  @Override
  public Byte readData() {
    return 0;
  }

  @Override
  public void writeData(Byte data) {
    vertx.eventBus().send(portName, new JsonObject().put("data", data));
  }

  private void sendCommand(byte[] commandBytes) {
    if (commandBytes.length == 0) {
      return;
    }

    byte[] inputBuffer = new byte[commandBytes.length + 1]; //
    inputBuffer[0] = DataConstants.BOFCOMCHAR;
    System.arraycopy(commandBytes, 0, inputBuffer, 1, commandBytes.length);

    byte computedChecksum = DataUtils.computeChecksum(inputBuffer);
    byte[] checksumBytes = String.format("%02X", computedChecksum).getBytes(StandardCharsets.US_ASCII);
    byte[] finalMessage = DataUtils.concatBuffer(inputBuffer, checksumBytes);

    try {
      serialPort.writeBytes(finalMessage, finalMessage.length, 0);
    } catch (Exception e) {
      System.err.println("Error writing to serial port: " + e.getMessage());
    }
  }


  public void commandEchoResponse(byte[] commandBuffer) {
    byte[] inputBuffer = new byte[commandBuffer.length + 2]; // +2 for BOF and checksum
    System.arraycopy(commandBuffer, 0, inputBuffer, 1, commandBuffer.length);
    inputBuffer[0] = DataConstants.BOFRESPCHAR;

    byte checksumComputed = DataUtils.computeChecksum(inputBuffer);

    String checksumToAsciiHex = String.format("%02x", checksumComputed).toUpperCase();
    byte[] checksumAsciiHexBytes = checksumToAsciiHex.getBytes(StandardCharsets.US_ASCII);

    byte[] finalTxBuff = DataUtils.concatBuffer(inputBuffer, checksumAsciiHexBytes);

    try {
      this.serialPort.writeBytes(finalTxBuff, finalTxBuff.length,0);
    } catch (Exception ex) {
      System.err.println("Error opening/writing to serial port :: " + ex.getMessage());
    }
  }

  public void sendDeviceID() {
    byte[] deviceIDCommandResponse = { 0x52 };
    byte[] devID = "0161".getBytes(StandardCharsets.US_ASCII);
    byte[] devName = "'SafetyBox'".getBytes(StandardCharsets.US_ASCII);
    byte[] devRevision = "01.03".getBytes(StandardCharsets.US_ASCII);
    byte[] medibusVer = ":06.00".getBytes(StandardCharsets.US_ASCII);

    byte[] txBuffer = new byte[
      deviceIDCommandResponse.length +
        devID.length +
        devName.length +
        devRevision.length +
        medibusVer.length
      ];

    System.arraycopy(deviceIDCommandResponse, 0, txBuffer, 0, deviceIDCommandResponse.length);
    System.arraycopy(devID, 0, txBuffer, deviceIDCommandResponse.length, devID.length);
    System.arraycopy(devName, 0, txBuffer, deviceIDCommandResponse.length + devID.length, devName.length);
    System.arraycopy(devRevision, 0, txBuffer, deviceIDCommandResponse.length + devID.length + devName.length, devRevision.length);
    System.arraycopy(medibusVer, 0, txBuffer, deviceIDCommandResponse.length + devID.length + devName.length + devRevision.length, medibusVer.length);

    commandEchoResponse(txBuffer);
  }


  public void setConfiguredDataStreams(boolean disable){
    if (this.waveFormType == 0) return;
    setDataStreams(DataConstants.SC_DATASTREAM_1_4, disable);
    if (this.waveFormType == 4) {
      setDataStreams(DataConstants.SC_DATASTREAM_5_8, disable);
      setDataStreams(DataConstants.SC_DATASTREAM_9_12, disable);
    }
  }

  public void setDataStreams(byte syncCommand, boolean disable) {
    logger.info("actually settting data streams");
    // enable or disable data streams
    byte syncByte = (byte) 0xD0;
    byte syncArgument = (byte) 0xCF;
    if (disable) {
      syncArgument = (byte) 0xC0;
    }
    byte endSyncByte = (byte) 0xC0;

    ArrayList<Byte> tempTxBuffList = new ArrayList<>();

    tempTxBuffList.add(syncByte);
    tempTxBuffList.add(syncCommand);
    tempTxBuffList.add(syncArgument);
    tempTxBuffList.add(endSyncByte);
    tempTxBuffList.add(endSyncByte);

    byte[] finalBuffer = new byte[tempTxBuffList.size()];
    for (int i = 0; i < tempTxBuffList.size(); i++) {
      finalBuffer[i] = tempTxBuffList.get(i);
    }

    this.serialPort.writeBytes(finalBuffer, finalBuffer.length, 0);
  }

public void sendConfigureRealtimeTransmission(Map<Byte, Byte> dataCodeToMultiplier) {
    try {
      byte bof = DataConstants.BOFCOMCHAR; // ESC (0x1B)
      byte commandCode = 0x54; // 'T' for Configure Realtime Transmission

      List<Byte> commandBuffer = new ArrayList<>();
      commandBuffer.add(bof);
      commandBuffer.add(commandCode);

      for (Map.Entry<Byte, Byte> entry : dataCodeToMultiplier.entrySet()) {
        byte dataCode = entry.getKey();
        byte multiplier = entry.getValue();

        String dataCodeHex = String.format("%02X", dataCode);
        String multiplierHex = String.format("%02X", multiplier);

        for (char c : dataCodeHex.toCharArray()) {
          commandBuffer.add((byte) c);
        }
        for (char c : multiplierHex.toCharArray()) {
          commandBuffer.add((byte) c);
        }
      }

      byte[] checksumInput = new byte[commandBuffer.size()];
      for (int i = 0; i < commandBuffer.size(); i++) {
        checksumInput[i] = commandBuffer.get(i);
      }

      byte checksum = DataUtils.computeChecksum(checksumInput);
      String checksumHex = String.format("%02X", checksum);

      for (char c : checksumHex.toCharArray()) {
        commandBuffer.add((byte) c);
      }

      commandBuffer.add((byte) 0x0D); // CR

      byte[] finalCommand = new byte[commandBuffer.size()];
      for (int i = 0; i < commandBuffer.size(); i++) {
        finalCommand[i] = commandBuffer.get(i);
      }

      serialPort.writeBytes(finalCommand, finalCommand.length);
      logger.info("Sent Configure Realtime Transmission command: " + Arrays.toString(finalCommand));
    } catch (Exception e) {
      logger.severe("Error sending Configure Realtime Transmission: " + e.getMessage());
    }
  }
}
