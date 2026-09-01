package com.framed.core;

import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;

import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * An abstract base class for application services that provides access to an {@link EventBus}
 * for inter-component communication / services and a {@link Logger} for logging.
 *
 * <p>Subclasses can use the event bus to send or receive messages and the logger for
 * diagnostic output. This class also provides a default {@link #stop()} method that can
 * be overridden to implement custom shutdown logic.</p>
 */
public abstract class Service {

  /**
   * Topic suffix for the producer&rarr;sink address-discovery protocol.
   *
   * <p>A producing service announces each of its output channel names on the topic
   * {@code "<group>" + ADDRESS_REGISTRY_SUFFIX} (see {@link #announceAddress(String, String)}).
   * Sinks such as dispatchers and writers subscribe to that same topic (using
   * {@link #addressRegistry(String)}) to dynamically discover and bind the channels a
   * producer emits. The {@code group} is typically a device name or a logical producer id.</p>
   */
  public static final String ADDRESS_REGISTRY_SUFFIX = ".addresses";

  /**
   * The event bus used for communication between components.
   */
  protected EventBus eventBus;

  /**
   * Logger instance for this service, initialized with the class name.
   */
  protected final Logger logger;

  /**
   * Shared timestamp formatter (see {@link Timer#formatter}) available to all services for
   * consistent serialization and parsing of timestamps.
   */
  protected static final DateTimeFormatter formatter = Timer.formatter;



  /**
   * Creates a new service instance with the specified event bus.
   *
   * @param eventBus the event bus used for communication
   */
  protected Service(EventBus eventBus) {
    this.eventBus = eventBus;
    this.logger = Logger.getLogger(getClass().getName());
  }

  /**
   * Returns the address-discovery topic for the given producer {@code group}.
   *
   * @param group device name or logical producer id
   * @return the topic sinks subscribe to in order to discover {@code group}'s output channels
   */
  public static String addressRegistry(String group) {
    return group + ADDRESS_REGISTRY_SUFFIX;
  }

  /**
   * Announces that {@code address} is an output channel of the producer {@code group}, so that
   * sinks subscribed via {@link #subscribeToAnnouncements(String, Consumer)} can discover and bind
   * it.
   *
   * <p><b>This call is synchronous with respect to binding.</b> Every subscriber registered through
   * {@link #subscribeToAnnouncements(String, Consumer)} has finished binding {@code address} by the
   * time this method returns, so a producer may announce a channel and publish its first sample on
   * the very next statement without losing it.</p>
   *
   * @param group   device name or logical producer id this service emits under
   * @param address the output channel name being announced
   * @see #subscribeToAnnouncements(String, Consumer)
   */
  protected void announceAddress(String group, String address) {
    eventBus.publish(addressRegistry(group), address);
  }

  /**
   * Subscribes to {@code group}'s address-discovery topic, so this service learns each output
   * channel the producer announces and can bind a handler to it.
   *
   * <p>The subscription is registered with {@link DispatchMode#SEQUENTIAL}, which is what makes the
   * discovery handshake race-free: {@code binder} runs inline on the announcing thread, so
   * {@link #announceAddress(String, String)} cannot return before every sink has bound. Registering
   * for announcements with {@code eventBus.register(addressRegistry(group), ..)} instead leaves the
   * binding to run on some other thread, and under {@link DispatchMode#PER_HANDLER} — the mode a
   * deployment runs — the producer's first samples are then published to an address nobody is
   * listening on yet and are silently discarded. <b>Sinks must use this method rather than
   * subscribing to {@link #addressRegistry(String)} directly.</b></p>
   *
   * <p><b>Threading:</b> {@code binder} runs on whichever thread called
   * {@link #announceAddress(String, String)}, and different producers announce on different
   * threads, so it must be safe to call concurrently. It should also be cheap and must not block:
   * it is executing inside the producer's publish path. Binding a handler is the only work it
   * should do.</p>
   *
   * @param group  device name or logical producer id whose announcements to follow
   * @param binder invoked once per announcement with the announced address
   */
  protected void subscribeToAnnouncements(String group, Consumer<String> binder) {
    eventBus.register(addressRegistry(group),
            msg -> binder.accept(String.valueOf(msg)),
            DispatchMode.SEQUENTIAL);
  }

  /**
   * Stops the service.
   * <p>By default, this method logs a message indicating that no stop logic is implemented.
   * Subclasses should override this method to provide custom shutdown behavior when necessary.</p>
   */
  public void stop(){
    logger.info("No stop logic implemented for Service: %s".formatted(this.getClass().getName()));
  }
}
