package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class TaxiOrderService {
    
    private final TaxiOrderRepository taxiOrderRepo;

    public TaxiOrderService(TaxiOrderRepository taxiOrderRepo) {
        this.taxiOrderRepo = taxiOrderRepo;
    }

    public String createTaxiOrder(String customerPhone, String pickUp, String destination){
        TaxiOrder taxiOrder = new TaxiOrder(customerPhone,pickUp, destination );
        taxiOrderRepo.save(taxiOrder);
        return
                """
                ✅ ההזמנה התקבלה!
                🚕 מאיפה: %s
                🎯 לאן: %s
                """.formatted(pickUp, destination);
    }
}
