/**
 * FRAMED core SDK: the event bus, service model, remote transports, orchestrator, and the
 * abstract extension points consumers implement (Protocol, Parser, Writer, Dispatcher, Reactor).
 *
 * <p>Only the packages exported below form the public API. {@code org.json} is re-exported
 * ({@code requires transitive}) because its {@code JSONObject} type appears in the public
 * API surface (e.g. the dispatch layer and event-bus message payloads).</p>
 */
module com.framed.core {

  // --- Dependencies ---
  requires java.logging;
  requires transitive org.json;

  // --- Public API ---
  exports com.framed.core;
  exports com.framed.core.utils;
  exports com.framed.core.remote;
  exports com.framed.core.local;
  exports com.framed.core.spi;

  exports com.framed.io.protocol;
  exports com.framed.io.parser;
  exports com.framed.io.writer;
  exports com.framed.io.dispatch;

  exports com.framed.arn;
  exports com.framed.orchestrator;

  // --- Service: deployment validators discovered by the orchestrator ---
  uses com.framed.core.spi.DeploymentValidator;
  provides com.framed.core.spi.DeploymentValidator with com.framed.arn.ARN;
}