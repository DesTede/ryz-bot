package com.example.yanivbot.Models;

public enum ConversationState {
    START,                                  // Initial state, waiting for name
    START_MENU,                             // After name capture, showing service menu (NEW!)

    // Taxi flow
    TAXI_SERVICE,                           // (deprecated - use START_MENU instead)
    TAXI_CAR_TYPE,                          // Choosing car type
    TAXI_PICKUP,
    AWAITING_PICKUP_SELECTION,              // Entering pickup location
    TAXI_DESTINATION,
    AWAITING_DESTINATION_SELECTION,         // Entering destination
    TAXI_NOTES,                             // Optional notes
    AWAITING_TAXI_ORDER_CONFIRMATION,       // Awaiting yes/no confirmation

    // Delivery flow
    
    DELIVERY_CUSTOMER_PHONE,                // Business entering customer phone
    DELIVERY_AWAITING_CUSTOMER_CONFIRM,
    DELIVERY_CUSTOMER_NAME,
    DELIVERY_ADDRESS,                       // Entering delivery address
    AWAITING_DELIVERY_ADDRESS_SELECTION,    // Choosing delivery address from Places suggestions
    DELIVERY_READY_TIME,                    // When order will be ready
    DELIVERY_PRICE,                         // Delivery fee
    DELIVERY_NOTES,                         // Optional notes
    DELIVERY_AWAITING_CONFIRMATION,                     //Confirmation
    
    
    BUSINESS_MENU,                          // Business owner main menu

    // Driver location
    AWAITING_DRIVER_LOCATION,                // Driver sharing location on shift start
    AWAITING_RATING_COMMENT
    }