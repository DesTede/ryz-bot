package com.example.yanivbot.Entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor
@Data
public class Business {
    
    @Id
    private long id;
    private String name;
    private String phone;
    private Boolean active;

    public Business(String name, String phone, Boolean active) {
        this.name = name;
        this.phone = phone;
        this.active = active;
    }
    
}
