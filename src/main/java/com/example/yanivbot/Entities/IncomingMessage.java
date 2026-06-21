package com.example.yanivbot.Entities;

public class IncomingMessage {

    private String phone;
    private String text;
    private String messageId;
    private Double latitude;
    private Double longitude;

    public IncomingMessage() {
    }

    public IncomingMessage(String phone, String text, String messageId) {
        this.phone = phone;
        this.text = text;
        this.messageId = messageId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public boolean hasLocation() {
        return latitude != null && longitude != null;
    }
}