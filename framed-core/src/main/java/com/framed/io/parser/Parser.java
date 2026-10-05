// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.io.parser;

import com.framed.core.EventBus;
import com.framed.core.Service;

/**
 * Abstract base for services that turn raw input into events published on the event bus.
 *
 * <p>A parser consumes raw messages of type {@code T} (for example a line of text or a binary
 * frame received from a device) and publishes the decoded values as events for downstream
 * services to consume.</p>
 *
 * @param <T> the type of raw input this parser decodes
 */
public abstract class Parser<T> extends Service {
  /**
   * Creates a new parser bound to the given event bus.
   *
   * @param eventBus the event bus used to publish parsed events
   */
  protected Parser(EventBus eventBus) {
    super(eventBus);
  }

  /**
   * Parses a raw input message and publishes the resulting events on the event bus.
   *
   * @param message    the raw input to parse
   * @param deviceName the identifier of the device the message originated from
   */
  public abstract void parse(T message, String deviceName);
}
