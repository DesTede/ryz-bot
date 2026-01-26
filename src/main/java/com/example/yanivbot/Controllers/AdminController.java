package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("admin")
public class AdminController {
    
    private final TaxiOrderRepository taxiOrderRepo;
    private final DeliveryOrderRepository deliveryOrderRepo;

    public AdminController(TaxiOrderRepository taxiOrderRepo,
                           DeliveryOrderRepository deliveryOrderRepo) {
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
    }
    
    @GetMapping("taxi-orders")
    public List<TaxiOrder> taxiyOrders() {
        return taxiOrderRepo.findAll();
    }
    
    @GetMapping("delivery-orders")
    public List<DeliveryOrder> deliveryOrders() { 
        return deliveryOrderRepo.findAll();
    }
    
}
