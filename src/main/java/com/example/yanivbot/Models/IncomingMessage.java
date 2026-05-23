package com.example.yanivbot.Models;

public class IncomingMessage {

    private String phone;
    private String text;
    private String messageId;

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
}