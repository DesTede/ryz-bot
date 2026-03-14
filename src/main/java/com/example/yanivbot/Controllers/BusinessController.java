package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Business;
import com.example.yanivbot.Repositories.BusinessRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/businesses")
public class BusinessController {

    private final BusinessRepository businessRepository;

    public BusinessController(BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
    }

    // GET /admin/businesses
    @GetMapping
    public List<Business> getAllBusinesses() {
        return businessRepository.findAll();
    }

    // POST /admin/businesses
    // Body: { "name": "פיצה ישראל", "phone": "+972549711059", "address": "רוטשילד 1, תל אביב", "active": true }
    @PostMapping
    public ResponseEntity<Business> addBusiness(@RequestBody Business business) {
        Business saved = businessRepository.save(business);
        return ResponseEntity.ok(saved);
    }

    // PUT /admin/businesses/{id}
    @PutMapping("/{id}")
    public ResponseEntity<Business> updateBusiness(@PathVariable long id, @RequestBody Business updated) {
        Business business = businessRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Business not found"));
        business.setName(updated.getName());
        business.setPhone(updated.getPhone());
        business.setAddress(updated.getAddress());
        business.setActive(updated.getActive());
        businessRepository.save(business);
        return ResponseEntity.ok(business);
    }

    // DELETE /admin/businesses/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteBusiness(@PathVariable long id) {
        businessRepository.deleteById(id);
        return ResponseEntity.ok("Business deleted");
    }
}