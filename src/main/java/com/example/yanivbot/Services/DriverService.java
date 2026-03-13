package com.example.yanivbot.Services;

//import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Repositories.DriverRepository;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DriverService {
    
    private final DriverRepository driverRepo;
    private final WhatsappService whatsappService;

    public DriverService(DriverRepository driverRepo, WhatsappService whatsappService) {
        this.driverRepo = driverRepo;
        this.whatsappService = whatsappService;
    }

    public void dispatchToDrivers(DriverType type, String message) throws UnsupportedEncodingException {
        List<Driver> drivers = driverRepo.findByActiveAndType(true, type);
        for (Driver driver : drivers) {
            whatsappService.sendText(driver.getPhone(), message);
        }
    }

    public List<Driver> getActiveDrivers(DriverType type) {
        return driverRepo.findByActiveAndType(true, type);
    }

    public void updateDriverLocation(String phone, double latitude, double longitude) {
        driverRepo.findDriverByPhone(phone).ifPresent(driver -> {
            driver.setLatitude(latitude);
            driver.setLongitude(longitude);
            driver.setLocationUpdatedAt(LocalDateTime.now());
            driverRepo.save(driver);
        });
    }
}
