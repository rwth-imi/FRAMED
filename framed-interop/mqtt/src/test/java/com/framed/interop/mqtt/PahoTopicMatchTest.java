package com.framed.interop.mqtt;

import org.junit.jupiter.api.Test;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PahoTopicMatchTest {

  @Test
  void exactAndWildcardMatching() {
    assertTrue(PahoMqttTransport.topicMatches("framed/a/b", "framed/a/b"));
    assertFalse(PahoMqttTransport.topicMatches("framed/a/b", "framed/a/c"));

    assertTrue(PahoMqttTransport.topicMatches("framed/+/b", "framed/a/b"));
    assertFalse(PahoMqttTransport.topicMatches("framed/+/b", "framed/a/c"));
    assertFalse(PahoMqttTransport.topicMatches("framed/+/b", "framed/a/b/c"));

    assertTrue(PahoMqttTransport.topicMatches("framed/#", "framed/a/b/c"));
    assertTrue(PahoMqttTransport.topicMatches("framed/#", "framed"));
    assertFalse(PahoMqttTransport.topicMatches("framed/#", "other/a"));
  }

  @Test
  void overlappingFiltersInvokeTheSameHandlerOnlyOnce() {
    List<String> calls = new ArrayList<>();
    BiConsumer<String, byte[]> shared = (topic, payload) -> calls.add("shared:" + topic);
    BiConsumer<String, byte[]> other = (topic, payload) -> calls.add("other:" + topic);

    List<Map.Entry<String, BiConsumer<String, byte[]>>> subscriptions = List.of(
        new SimpleImmutableEntry<>("framed/#", shared),
        new SimpleImmutableEntry<>("framed/+/etCO2", shared), // overlaps the first filter
        new SimpleImmutableEntry<>("framed/dev/etCO2", other),
        new SimpleImmutableEntry<>("elsewhere/#", other));

    PahoMqttTransport.dispatchArrived("framed/dev/etCO2", new byte[0], subscriptions);

    assertEquals(List.of("shared:framed/dev/etCO2", "other:framed/dev/etCO2"), calls,
        "a handler under overlapping filters fires once; non-matching filters not at all");
  }
}
