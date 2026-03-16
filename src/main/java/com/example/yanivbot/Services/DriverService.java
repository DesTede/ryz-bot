package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Repositories.DriverRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DriverService {

    private final DriverRepository driverRepo;
    private final WhatsappService whatsappService;

    public DriverService(DriverRepository driverRepo, WhatsappService whatsappService) {
        this.driverRepo = driverRepo;
        this.whatsappService = whatsappService;
    }

    public void dispatchToClosestDrivers(DriverType type, String message, double lat, double lng) {
        // max radius added
        int maxDrivers = type == DriverType.TAXI ? 5 : 2;
        List<Driver> drivers = getClosestDrivers(type, lat, lng, 5);

        if (drivers.isEmpty()) {
            System.err.println("No drivers location found for type " + type + " falling back to all drivers");
            dispatchToDrivers(type, message); // fallback
            return;
        }

        for (Driver driver : drivers) {
            whatsappService.sendSafeText(driver.getPhone(), message);
        }
    }

    public void dispatchToDrivers(DriverType type, String message) {
        List<Driver> drivers = getActiveDrivers(type); 
        System.out.println("Dispatching to " + drivers.size() + " drivers of type " + type);

        for (Driver driver : drivers) {
            whatsappService.sendSafeText(driver.getPhone(), message);
        }
    }

    public Driver findByPhone(String phone){
        return driverRepo.findDriverByPhone(phone).orElse(null);    
    }
    
    public String clockIn(String phone) {
        Driver driver = findByPhone(phone);
        if (driver == null)
            return "❌ הטלפון שלך לא רשום במערכת כנהג.";
        
        driver.setActive(true);
        driverRepo.save(driver);
        return "✅ התחלת משמרת! תקבל הזמנות מעכשיו.";
    }

    public void clockOut(String phone) {
        Driver driver = findByPhone(phone);
        if (driver == null)
            return;
        
        driver.setActive(false);
        driverRepo.save(driver);
    }
    
    public List<Driver> getActiveDrivers(DriverType type) {
        return driverRepo.findByActiveAndTypeIn(true, List.of(type,DriverType.BOTH));
    }

    public double[] getDriverLocation(String phone){
        Driver driver = findByPhone(phone);
        if (driver == null || driver.getLatitude() == 0)
            return null;
        return new double[]{driver.getLatitude(), driver.getLongitude()};
        
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
        List<Driver> drivers = getActiveDrivers(type);

        final double MAX_RADIUS_KM = 5.0;

        List<Driver> closeDrivers = drivers.stream()
                .filter(d -> d.getLatitude() != 0 && d.getLongitude() != 0)
                .filter(d -> calculateDistance(lat, lng, d.getLatitude(), d.getLongitude()) <= MAX_RADIUS_KM)
                .sorted((a, b) -> {
                    double distA = calculateDistance(lat, lng, a.getLatitude(), a.getLongitude());
                    double distB = calculateDistance(lat, lng, b.getLatitude(), b.getLongitude());
                    return Double.compare(distA, distB);
                })
                .limit(maxDrivers)
                .collect(Collectors.toList());

        if (closeDrivers.isEmpty()) {
            System.out.println("No drivers within " + MAX_RADIUS_KM + "km, falling back to all active drivers");
            return drivers.stream()
                    .filter(d -> d.getLatitude() != 0 && d.getLongitude() != 0)
                    .sorted((a, b) -> {
                        double distA = calculateDistance(lat, lng, a.getLatitude(), a.getLongitude());
                        double distB = calculateDistance(lat, lng, b.getLatitude(), b.getLongitude());
                        return Double.compare(distA, distB);
                    })
                    .limit(maxDrivers)
                    .collect(Collectors.toList());
        }

        return closeDrivers;
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
