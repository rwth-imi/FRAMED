package com.framed.interop.mqtt;

import org.junit.jupiter.api.Test;

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
}
