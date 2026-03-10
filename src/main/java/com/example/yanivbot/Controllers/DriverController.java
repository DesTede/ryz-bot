package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Repositories.DriverRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/drivers")
public class DriverController {

    private final DriverRepository driverRepository;

    public DriverController(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }
 
    // GET /admin/drivers
    @GetMapping
    public List<Driver> getAllDrivers() {
        return driverRepository.findAll();
    }  

    // GET /admin/drivers/taxi
    @GetMapping("/taxi")
    public List<Driver> getTaxiDrivers() {
        return driverRepository.findByActiveAndType(true, DriverType.TAXI);
    }

    // GET /admin/drivers/delivery
    @GetMapping("/delivery")
    public List<Driver> getDeliveryDrivers() {
        return driverRepository.findByActiveAndType(true, DriverType.DELIVERY);
    }

    // POST /admin/drivers
    // Body: { "name": "יוסי", "phone": "+972501234567", "type": "TAXI" }
    @PostMapping
    public ResponseEntity<Driver> addDriver(@RequestBody Driver driver) {
        driver.setActive(true);
        Driver saved = driverRepository.save(driver);
        return ResponseEntity.ok(saved);
    }

    // PUT /admin/drivers/{id}/deactivate
    @PutMapping("/{id}/deactivate")
    public ResponseEntity<String> deactivateDriver(@PathVariable long id) {
        Driver driver = driverRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Driver not found"));
        driver.setActive(false);
        driverRepository.save(driver);
        return ResponseEntity.ok("Driver deactivated");
    }

    // PUT /admin/drivers/{id}/activate
    @PutMapping("/{id}/activate")
    public ResponseEntity<String> activateDriver(@PathVariable long id) {
        Driver driver = driverRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Driver not found"));
        driver.setActive(true);
        driverRepository.save(driver);
        return ResponseEntity.ok("Driver activated");
    }

    // DELETE /admin/drivers/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteDriver(@PathVariable long id) {
        driverRepository.deleteById(id);
        return ResponseEntity.ok("Driver deleted");
    }
}