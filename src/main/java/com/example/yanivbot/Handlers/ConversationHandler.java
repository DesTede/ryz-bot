package com.example.yanivbot.Handlers;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Entities.IncomingMessage;

/**
 * Interface for handling conversation flows.
 * 
 * Each handler is responsible for:
 * - Managing a specific conversation type (taxi, delivery, driver, business owner)
 * - Handling all state transitions for that flow
 * - Returning appropriate messages to send to the user
 * 
 * MessageRouter delegates to the appropriate handler based on conversation state.
 */
public interface ConversationHandler {
    
    /**
     * Process an incoming message and return a response.
     * 
     * @param convo The current conversation with state information
     * @param message The incoming message from the user
     * @return The response message to send to the user, or null if no response needed
     */
    String handleMessage(Conversation convo, IncomingMessage message);
}
