package com.example.yanivbot.Models;

public enum ConversationState {
    START,                                  // Initial state, waiting for name
    START_MENU,                             // After name capture, showing service menu (NEW!)

    // Taxi flow
    TAXI_SERVICE,                           // (deprecated - use START_MENU instead)
    TAXI_CAR_TYPE,                          // Choosing car type
    TAXI_PICKUP,                            // Entering pickup location
    TAXI_DESTINATION,                       // Entering destination
    TAXI_NOTES,                             // Optional notes
    AWAITING_TAXI_ORDER_CONFIRMATION,       // Awaiting yes/no confirmation

    // Delivery flow
    DELIVERY_CUSTOMER_NAME,
    DELIVERY_CUSTOMER_PHONE,                // Business entering customer phone
    DELIVERY_ADDRESS,                       // Entering delivery address
    DELIVERY_READY_TIME,                    // When order will be ready
    DELIVERY_PRICE,                         // Delivery fee
    DELIVERY_NOTES,                         // Optional notes
Delivery_confirmation,                     //Confirmation          

    // Business owner
    BUSINESS_MENU,                          // Business owner main menu

    // Driver location
    AWAITING_DRIVER_LOCATION                // Driver sharing location on shift start
}