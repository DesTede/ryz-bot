package com.example.yanivbot.Entities;

import com.example.yanivbot.Models.ConversationState;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Entity
//@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Data
public class Conversation {
    
    @Id
    private String phone;
    
    @Enumerated(EnumType.STRING)
    private ConversationState state;
    
    private String tempData;

    public Conversation(){}
    
    public Conversation(String phone, ConversationState state) {
        this.phone = phone;
        this.state = state;
    }

}
