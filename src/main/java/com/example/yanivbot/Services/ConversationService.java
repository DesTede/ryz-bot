package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import com.example.yanivbot.Repositories.ConversationRepository;
import org.springframework.stereotype.Service;

@Service
public class ConversationService {
    
    private final ConversationRepository convoRepo;

    public ConversationService(ConversationRepository conversationRepo) {
        this.convoRepo = conversationRepo;
    }
    
    public Conversation getOrCreate(String phone){
        return convoRepo.findById(phone)
                .orElseGet(() -> convoRepo.save(new Conversation(phone, ConversationState.START)));
    }
    
    public void updateState(Conversation convo, ConversationState newState){
        
        convo.setState(newState);
        convoRepo.save(convo);
    }
    
    public void saveTempData(Conversation convo, String data){
        convo.setTempData(data);
        convoRepo.save(convo);
    }
    
    public void updateStateByPhone(String phone, ConversationState state){
        Conversation convo = getOrCreate(phone);
        updateState(convo, state);
    }
}
