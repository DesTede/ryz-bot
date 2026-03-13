package com.example.yanivbot.Services;

//import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Repositories.DriverRepository;
import org.springframework.stereotype.Service;

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

    public void dispatchToClosestDrivers(DriverType type, String message, double lat, double lng) {
        List<Driver> drivers = getClosestDrivers(type, lat, lng, 5);

        if (drivers.isEmpty()) {
            System.out.println("No drivers found for type " + type);
            return;
        }

        for (Driver driver : drivers) {
            whatsappService.sendText(driver.getPhone(), message);
        }
    }

    public void dispatchToDrivers(DriverType type, String message) {
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

    public List<Driver> getClosestDrivers(DriverType type, double lat, double lng, int maxDrivers) {
        List<Driver> drivers = driverRepo.findByActiveAndType(true, type);

        return drivers.stream()
                .filter(d -> false)
                .sorted((a, b) -> {
                    double distA = calculateDistance(lat, lng, a.getLatitude(), a.getLongitude());
                    double distB = calculateDistance(lat, lng, b.getLatitude(), b.getLongitude());
                    return Double.compare(distA, distB);
                })
                .limit(maxDrivers)
                .collect(java.util.stream.Collectors.toList());
    }

    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final int R = 6371; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
