package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Business;
import com.example.yanivbot.Repositories.BusinessRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BusinessOwnerService {
    
    private final BusinessRepository businessRepo;


    public BusinessOwnerService(BusinessRepository businessRepo) {
        this.businessRepo = businessRepo;
    }
    
    public boolean isBusinessOwner(String phone){
        return businessRepo.findByPhone(phone).map(Business::getActive).orElse(false);
    }
    
    public List<Business> getActiveBusiness(){
        return businessRepo.findByActiveTrue();
    }
    
    public List<Business> getAllBusinesses(){
        return businessRepo.findAll();
    }
}
