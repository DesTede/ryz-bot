package com.example.yanivbot.Models;

/**
 * Taxi Order Status Enum
 *
 * CREATED       - Order just created, dispatching to drivers
 * ASSIGNED      - Driver claimed/accepted the order (changed from TAKEN)
 * CONFIRMED     - Driver confirmed they're on the way
 * COMPLETED     - Driver completed the ride
 * CANCELLED     - Order cancelled by customer or driver
 */
public enum TaxiOrderStatus {
    CREATED,      // Initial state
    ASSIGNED,     // Driver has claimed the order (changed from TAKEN)
    CONFIRMED,    // Driver confirmed pickup/en route
    COMPLETED,    // Ride completed
    CANCELLED     // Order cancelled
}