package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Repositories.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository convoRepo;

    public ConversationService(ConversationRepository convoRepo) {
        this.convoRepo = convoRepo;
    }

    public Conversation getOrCreate(String phone) {
        logger.info("========== getOrCreate ==========");
        logger.info("Phone: {}", phone);

        Conversation convo = convoRepo.findById(phone).orElse(null);

        if (convo == null) {
            logger.info("Conversation NOT FOUND - creating new one");
            convo = new Conversation();
            convo.setPhone(phone);
            convo.setState(ConversationState.START);
            convo.setTempData("");
            convoRepo.save(convo);
            logger.info("Created new conversation with state: {}", convo.getState());
        } else {
            logger.info("Conversation FOUND");
            logger.info("  Phone: {}", convo.getPhone());
            logger.info("  State: {}", convo.getState());
            logger.info("  TempData: {}", convo.getTempData());
        }

        logger.info("================================");
        return convo;
    }

    public void updateState(Conversation convo, ConversationState newState) {
        logger.info("========== updateState ==========");
        logger.info("Phone: {}", convo.getPhone());
        logger.info("Old State: {}", convo.getState());
        logger.info("New State: {}", newState);

        convo.setState(newState);

        Conversation saved = convoRepo.save(convo);

        logger.info("State updated in database");
        logger.info("Saved state: {}", saved.getState());
        logger.info("================================");
    }

    public void saveTempData(Conversation convo, String data) {
        logger.info("========== saveTempData ==========");
        logger.info("Phone: {}", convo.getPhone());
        logger.info("Old TempData: {}", convo.getTempData());
        logger.info("New TempData: {}", data);

        convo.setTempData(data);

        Conversation saved = convoRepo.save(convo);

        logger.info("TempData updated in database");
        logger.info("Saved TempData: {}", saved.getTempData());
        logger.info("================================");
    }

    public void updateStateByPhone(String phone, ConversationState state) {
        logger.info("========== updateStateByPhone ==========");
        logger.info("Phone: {}", phone);
        logger.info("New State: {}", state);

        Conversation convo = getOrCreate(phone);
        updateState(convo, state);

        logger.info("======================================");
    }
}