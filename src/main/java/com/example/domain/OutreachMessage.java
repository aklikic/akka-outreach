package com.example.domain;

public record OutreachMessage(MessageType messageType, String body) {

    public enum MessageType {
        CONNECTION_REQUEST,
        FOLLOW_UP_1,
        FOLLOW_UP_2
    }
}