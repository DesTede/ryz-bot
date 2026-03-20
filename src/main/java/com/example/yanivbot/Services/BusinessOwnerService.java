package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Business;
import com.example.yanivbot.Repositories.BusinessRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class BusinessOwnerService {
    
    private final BusinessRepository businessRepo;
    
    
    
    public BusinessOwnerService(BusinessRepository businessRepo) {
        this.businessRepo = businessRepo;
    }

    /**
     * hardcoded numbers for tests
     */
//    private static final Set<String> BusinessOwners = Set.of("+972527033557", "+972549711059");

//    private static final Set<String> BusinessOwners = Set.of("+972586976238");

    public boolean isBusinessOwner(String phone){
        return businessRepo.findByPhone(phone).map(Business::getActive).orElse(false);
    }
    
    public List<Business> getActiveBusiness(){
        return businessRepo.findByActiveTrue();
    }
    
    public List<Business> getAllBusinesses(){
        return businessRepo.findAll();
    }
    
    public String getBusinessAddress(String phone){
        return businessRepo.findByPhone(phone).map(Business::getAddress).orElse(null);
    }

    public String getBusinessName(String phone) {
        return businessRepo.findByPhone(phone)
                .map(Business::getName)
                .orElse(null);
    }
}
