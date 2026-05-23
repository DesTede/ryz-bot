package com.example.yanivbot.Models;

public enum CarType {
    MOTORCYCLE("אופנוע"),
    PRIVATE_CAR("מכונית פרטית"),
    MINIVAN("מיניוואן");
    
    private final String hebrewName;
    
    CarType(String hebrewName) {
        this.hebrewName = hebrewName;
    }
    
    public String getHebrewName() {
        return hebrewName;
    }
    
    public static CarType fromHebrew(String hebrewName) {
        for (CarType type : CarType.values()) {
            if (type.hebrewName.equals(hebrewName)) {
                return type;
            }
        }
        return null;
    }
}
