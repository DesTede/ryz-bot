package com.example.yanivbot.Entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class BotConfig {

    @Id
    private String key;
    private String value;

    public BotConfig() {}

    public BotConfig(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    
    public void setKey(String key) { this.key = key; }
    
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
