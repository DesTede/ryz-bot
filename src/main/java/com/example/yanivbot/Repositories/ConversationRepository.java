package com.example.yanivbot.Repositories;

import com.example.yanivbot.Entities.Conversation;
import com.example.yanivbot.Models.ConversationState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation,String> {

    List<Conversation> findByStateInAndLastMessageTimeBetween(
            List<ConversationState> states, long from, long to);

    List<Conversation> findByStateInAndLastMessageTimeBetweenAndNudgedAt(
            List<ConversationState> states, long from, long to, long nudgedAt);
}
