package com.framed.core.remote;

public record RemoteMessage(String address, Object payload, String type) {}
