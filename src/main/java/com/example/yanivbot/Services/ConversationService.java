package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Repositories.ConversationRepository;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * [COMPLETE FILE]
 * Manages conversation state and persistence for users.
 *
 * NOTE: Welcome message is handled by MessageRouter, not here.
 * ConversationService only manages state and data persistence.
 */
@Service
public class ConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository convoRepo;

    public ConversationService(ConversationRepository convoRepo) {
        this.convoRepo = convoRepo;
    }

    /**
     * Get existing conversation or create new one
     * Returns conversation object without sending any messages
     */
    public Conversation getOrCreate(String phone) {
        Optional<Conversation> existing = convoRepo.findById(phone);

        if (existing.isPresent()) {
            Conversation convo = existing.get();
            logger.info("getOrCreate: Found existing conversation for {}", phone);
            logger.info("  State: {}", convo.getState());
            logger.info("  TempData: {}", convo.getTempData());
            return convo;
        }

        // Create new conversation
        logger.info("getOrCreate: Creating new conversation for {}", PhoneNumberUtil.maskPhoneNumberWithCountryCode(phone));
        Conversation convo = new Conversation();
        convo.setPhone(phone);
        convo.setState(ConversationState.START);
        convo.setTempData("");
        convoRepo.save(convo);
        logger.info("getOrCreate: Created new conversation with state START");
        return convo;
    }

    /**
     * Update conversation state and save to database
     */
    public void updateState(Conversation convo, ConversationState newState) {
        String phone = convo.getPhone();
        ConversationState oldState = convo.getState();

        logger.info("updateState: {} -> {} (was: {})", phone, newState, oldState);

        convo.setState(newState);
        Conversation saved = convoRepo.save(convo);

        logger.info("updateState: Saved to database with state: {}", saved.getState());
    }

    /**
     * Save temporary data and save to database
     */
    public void saveTempData(Conversation convo, String data) {
        String phone = convo.getPhone();
        String oldData = convo.getTempData();

        logger.info("saveTempData: {} | Old: '{}' -> New: '{}'", phone, oldData, data);

        convo.setTempData(data);
        convoRepo.save(convo);

        logger.info("saveTempData: Saved to database");
    }

    /**
     * Update state by phone number
     */
    public void updateStateByPhone(String phone, ConversationState state) {
        Conversation convo = getOrCreate(phone);
        updateState(convo, state);
    }

    /**
     * Update last message time to now (call on every inbound customer message)
     */
    public void updateLastMessageTime(String phone) {
        Conversation convo = getOrCreate(phone);
        convo.setLastMessageTime(System.currentTimeMillis());
        convoRepo.save(convo);
    }

    /**
     * Returns true if customer sent a message within the last 24 hours
     */
    public boolean isWithin24HourWindow(String phone) {
        Conversation convo = getOrCreate(phone);
        long lastTime = convo.getLastMessageTime();
        if (lastTime == 0) return false;
        long elapsed = System.currentTimeMillis() - lastTime;
        return elapsed < 24L * 60 * 60 * 1000;
    }

    /**
     * Reset conversation state and tempData to START (called after order completion)
     */
    public void resetConversationForCustomer(String phone) {
        Conversation convo = getOrCreate(phone);
        updateState(convo, ConversationState.START);
        saveTempData(convo, "");
    }

    /**
     * Find conversations that are mid-flow and idle between fromMs and toMs
     * Used by scheduler to send nudge or reset abandoned conversations
     */
    public List<Conversation> findIdleMidFlowConversations(List<ConversationState> states, long fromMs, long toMs) {
        return convoRepo.findByStateInAndLastMessageTimeBetween(states, fromMs, toMs);
    }
}