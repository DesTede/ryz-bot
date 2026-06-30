package com.example.yanivbot.Entities;

import com.example.yanivbot.Models.ConversationState;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Entity
//@NoArgsConstructor(access = AccessLevel.PROTECTED)
//@Data
public class Conversation {
    
    @Id
    private String phone;
    
    @Enumerated(EnumType.STRING)
    private ConversationState state;

    @Column(columnDefinition = "TEXT")
    private String tempData;
    
    private long lastMessageTime;
    private long nudgedAt;

    public Conversation(){}
    
    public Conversation(String phone, ConversationState state) {
        this.phone = phone;
        this.state = state;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public ConversationState getState() {
        return state;
    }

    public void setState(ConversationState state) {
        this.state = state;
    }

    public String getTempData() {
        return tempData;
    }

    public void setTempData(String tempData) {
        this.tempData = tempData;
    }

    public long getLastMessageTime() {
        return lastMessageTime;
    }

    public void setLastMessageTime(long lastMessageTime) {
        this.lastMessageTime = lastMessageTime;
    }

    public long getNudgedAt() {
        return nudgedAt;
    }

    public void setNudgedAt(long nudgedAt) {
        this.nudgedAt = nudgedAt;
    }
}
