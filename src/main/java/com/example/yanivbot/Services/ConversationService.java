package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Repositories.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Service
public class ConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository convoRepo;
    private final WhatsappService whatsappService;

    private static final String WELCOME_MESSAGE = "ברוכים הבאים ל־Movez — מזמינים נסיעה תוך שניות בוואטסאפ ⚡\nאז איך קוראים לך?";

    public ConversationService(ConversationRepository convoRepo, WhatsappService whatsappService) {
        this.convoRepo = convoRepo;
        this.whatsappService = whatsappService;
    }

    /**
     * Get or create conversation for a phone number
     * If conversation is in START state, send welcome message
     */
    public Conversation getOrCreate(String phone) {
        Optional<Conversation> existing = convoRepo.findById(phone);

        if (existing.isPresent()) {
            Conversation convo = existing.get();
            logger.info("getOrCreate: Found existing conversation for {}", phone);
            logger.info("  State: {}", convo.getState());
            logger.info("  TempData: {}", convo.getTempData());

            // If conversation is in START state, send welcome message
            if (convo.getState() == ConversationState.START) {
                logger.info("Conversation in START state - sending welcome message");
                whatsappService.sendSafeText(phone, WELCOME_MESSAGE);
            }

            return convo;
        }

        // Create new conversation
        logger.info("getOrCreate: Creating new conversation for {}", phone);
        Conversation convo = new Conversation();
        convo.setPhone(phone);
        convo.setState(ConversationState.START);
        convo.setTempData("");
        convoRepo.save(convo);
        logger.info("getOrCreate: Created new conversation with state START");

        // Send welcome message
        logger.info("Sending welcome message to new user");
        whatsappService.sendSafeText(phone, WELCOME_MESSAGE);

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
}