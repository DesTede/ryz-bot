package com.example.yanivbot.Models;

public enum DeliveryStatus {
    CREATED,
    READY,
    /**
     * להוסיף סטטוס של הזמנה תפוסה ע"י שליח
     */
    PICKED_UP,
    DELIVERED,
    CANCELLED
}
