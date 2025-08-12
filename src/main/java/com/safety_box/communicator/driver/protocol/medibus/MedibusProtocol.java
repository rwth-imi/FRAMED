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
    logger.info("Handling response with echo: " + stringToHex(echo) + " in state " + currentState);

    switch (currentState) {
      case INITIALIZING -> {
        switch (echo) {
          case "\u001bQ" -> {
            logger.info("ICC command received.");
            commandEchoResponse(DataConstants.poll_request_icc_msg);
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
          case "\u0001R" -> {
            logger.info("Device ID response received.");
            if (realTime) {
              logger.info("Realtime enabled. Transitioning to CONFIGURING.");
              currentState = State.CONFIGURING;
              sendCommand(DataConstants.poll_request_real_time_data_config);
            } else {
              logger.info("Transitioning to ACTIVE.");
              currentState = State.ACTIVE;
              //nopTimerID = vertx.setPeriodic(1500, id -> sendCommand(DataConstants.poll_request_no_operation));
            }
          }
        }
      }

      case CONFIGURING -> {
        switch (echo) {
          case "\u0001\u0030" -> {
            logger.info("NOP response received.");
            commandEchoResponse(DataConstants.poll_request_no_operation);
          }
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
            //nopTimerID = vertx.setPeriodic(1500, id -> sendCommand(DataConstants.poll_request_no_operation));
          }
          default -> {
            logger.warning("Unknown response in CONFIGURING: " + stringToHex(echo));
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
            sendCommand(DataConstants.poll_request_real_time_data_config);
          }
          default -> {
            logger.warning("Unknown response in ACTIVE: " + stringToHex(echo));
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
            logger.warning("Unhandled state or echo: " + currentState + " / " + stringToHex(echo));
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
    sendCommand(DataConstants.poll_request_stop_com); // STOP
    currentState = State.TERMINATING;
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

  private byte[] hexToBytes(String hex) {
    String[] parts = hex.split(" ");
    byte[] bytes = new byte[parts.length];
    for (int i = 0; i < parts.length; i++) {
      bytes[i] = (byte) Integer.parseInt(parts[i], 16);
    }
    return bytes;
  }

  private String stringToHex(String bytesAsString) {
    byte[] bytes = bytesAsString.getBytes(StandardCharsets.US_ASCII);

    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02X ", b));
    }
    return sb.toString().trim();
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
    byte[] devID = "0161".getBytes();
    byte[] devName = "'SafetyBox'".getBytes();
    byte[] devRevision = "01.03".getBytes();
    byte[] medibusVer = ":06.00".getBytes();

    byte[] txBuffer = new byte[deviceIDCommandResponse.length + devID.length + devName.length + devRevision.length + medibusVer.length];
    System.arraycopy(deviceIDCommandResponse, 0, txBuffer, 0, deviceIDCommandResponse.length);
    System.arraycopy(devID, 0, txBuffer, deviceIDCommandResponse.length, devID.length);
    System.arraycopy(devName, 0, txBuffer, deviceIDCommandResponse.length + devID.length, devName.length);
    System.arraycopy(devRevision, 0, txBuffer, deviceIDCommandResponse.length + devID.length + devName.length, devRevision.length);
    System.arraycopy(medibusVer, 0, txBuffer, deviceIDCommandResponse.length + devID.length + devName.length + devRevision.length, medibusVer.length);

    logger.log(Level.FINE, "Sending device ID");
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

}
