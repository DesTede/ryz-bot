package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Customer;
import com.example.yanivbot.Services.CustomerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/customers")
public class CustomerController {

    private final CustomerService customerService;

    @Value("${admin.api-key}")
    private String adminApiKey;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    private boolean isAuthorized(String key) {
        return adminApiKey != null && key != null
                && java.security.MessageDigest.isEqual(
                adminApiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Get all customers
     */
    @GetMapping
    public ResponseEntity<List<Customer>> getAllCustomers(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        List<Customer> customers = customerService.getAllCustomers();
        return ResponseEntity.ok(customers);
    }

    /**
     * Get customer by phone
     */
    @GetMapping("/{phone}")
    public ResponseEntity<Customer> getCustomer(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable String phone) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        Customer customer = customerService.getCustomer(phone);
        if (customer != null) {
            return ResponseEntity.ok(customer);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Get customer statistics
     */
    @GetMapping("/stats/overview")
    public ResponseEntity<CustomerService.CustomerStats> getStats(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        CustomerService.CustomerStats stats = customerService.getStatistics();
        return ResponseEntity.ok(stats);
    }

    /**
     * Export customers as CSV
     */
    @GetMapping("/export/csv")
    public ResponseEntity<String> exportAsCSV(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String csv = customerService.exportCustomersAsCSV();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customers_" + LocalDateTime.now() + ".csv\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(csv);
    }

    /**
     * Send message to specific customer
     */
    @PostMapping("/{phone}/send-message")
    public ResponseEntity<String> sendMessageToCustomer(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable String phone,
            @RequestBody Map<String, String> request) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String message = request.get("message");
        if (message == null || message.isEmpty()) {
            return ResponseEntity.badRequest().body("Message is required");
        }
        customerService.sendMessageToCustomer(phone, message);
        return ResponseEntity.ok("Message sent to customer: " + phone);
    }

    /**
     * Send message to all customers
     */
    @PostMapping("/send-all")
    public ResponseEntity<String> sendMessageToAll(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody Map<String, String> request) {
        if (!isAuthorized(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String message = request.get("message");
        if (message == null || message.isEmpty()) {
            return ResponseEntity.badRequest().body("Message is required");
        }
        customerService.sendMessageToAllCustomers(message);
        return ResponseEntity.ok("Message sent to all customers");
    }
}