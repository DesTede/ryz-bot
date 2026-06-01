package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Customer;
import com.example.yanivbot.Repositories.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class CustomerService {
    
    private static final Logger logger = LoggerFactory.getLogger(CustomerService.class);
    
    private final CustomerRepository customerRepo;
    private final WhatsappService whatsappService;
    
    public CustomerService(CustomerRepository customerRepo, WhatsappService whatsappService) {
        this.customerRepo = customerRepo;
        this.whatsappService = whatsappService;
    }
    
    /**
     * Save or update customer when they place an order
     * Phone is required, name comes from WhatsApp profile
     */
    public Customer saveOrUpdateCustomer(String phone, String name) {
        Optional<Customer> existing = customerRepo.findByPhone(phone);
        
        if (existing.isPresent()) {
            Customer customer = existing.get();
            customer.incrementDeliveryOrders();
            // Update name if it's empty
            if (customer.getName() == null || customer.getName().isEmpty()) {
                customer.setName(name);
            }
            customerRepo.save(customer);
            logger.info("Updated customer: {} (total orders: {})", phone, customer.getTotalOrders());
            return customer;
        } else {
            // New customer
            Customer newCustomer = new Customer(phone, name);
            newCustomer.incrementDeliveryOrders();
            newCustomer.setTotalDeliveryOrders(1);
            customerRepo.save(newCustomer);
            logger.info("Saved new customer: {}", phone);
            return newCustomer;
        }
    }

    /**
     * Register a new customer when they first chat (no order yet)
     * Only creates - never overwrites existing
     */
    public void registerNewCustomer(String phone, String name) {
        if (customerRepo.findByPhone(phone).isEmpty()) {
            Customer newCustomer = new Customer(phone, name);
            customerRepo.save(newCustomer);
            logger.info("Registered new customer on first chat: {}", phone);
        }
    }

    /**
     * Called when a taxi order is placed
     */
    public void recordTaxiOrder(String phone) {
        Customer customer = customerRepo.findByPhone(phone).orElse(new Customer(phone, "לקוח"));
        customer.incrementTaxiOrders();
        customerRepo.save(customer);
    }

    /**
     * Update lastMessageAt on every inbound message
     */
    public void updateLastMessageAt(String phone) {
        customerRepo.findByPhone(phone).ifPresent(customer -> {
            customer.setLastMessageAt(LocalDateTime.now());
            customerRepo.save(customer);
        });
    }
    
    /**
     * Get customer by phone
     */
    public Customer getCustomer(String phone) {
        return customerRepo.findByPhone(phone).orElse(null);
    }
    
    /**
     * Get all customers sorted by last order
     */
    public List<Customer> getAllCustomers() {
        return customerRepo.findAllByOrderByLastOrderAtDesc();
    }
    
    /**
     * Get all customers sorted by creation date
     */
    public List<Customer> getAllCustomersByCreatedDate() {
        return customerRepo.findAllByOrderByCreatedAtDesc();
    }
    
    /**
     * Get total number of customers
     */
    public long getTotalCustomers() {
        return customerRepo.count();
    }
    
    /**
     * Send message to specific customer
     */
    public void sendMessageToCustomer(String phone, String message) {
        Customer customer = getCustomer(phone);
        if (customer != null) {
            whatsappService.sendSafeText(phone, message);
            logger.info("Sent message to customer: {}", phone);
        } else {
            logger.warn("Customer not found: {}", phone);
        }
    }
    
    /**
     * Send message to all customers (with optional greeting)
     */
    public void sendMessageToAllCustomers(String message) {
        List<Customer> customers = getAllCustomers();
        logger.info("Sending message to {} customers", customers.size());
        
        for (Customer customer : customers) {
            String personalizedMessage = "שלום " + customer.getName() + ",\n\n" + message;
            whatsappService.sendSafeText(customer.getPhone(), personalizedMessage);
        }
        
        logger.info("Message sent to all customers");
    }
    
    /**
     * Export customers as CSV format
     */
    public String exportCustomersAsCSV() {
        List<Customer> customers = getAllCustomers();
        StringBuilder csv = new StringBuilder();
        
        // Header
        csv.append("שם,טלפון,אימייל,מועד הצטרפות,הזמנות כוללות,הזמנה אחרונה\n");
        
        // Data rows
        for (Customer customer : customers) {
            csv.append(customer.getName()).append(",");
            csv.append(customer.getPhone()).append(",");
            csv.append(customer.getEmail() != null ? customer.getEmail() : "").append(",");
            csv.append(customer.getCreatedAt()).append(",");
            csv.append(customer.getTotalOrders()).append(",");
            csv.append(customer.getLastOrderAt() != null ? customer.getLastOrderAt() : "").append("\n");
        }
        
        return csv.toString();
    }
    
    /**
     * Get customer statistics
     */
    public CustomerStats getStatistics() {
        List<Customer> customers = getAllCustomers();
        long totalCustomers = customers.size();
        long totalOrders = customers.stream().mapToLong(Customer::getTotalOrders).sum();
        double avgOrdersPerCustomer = totalCustomers > 0 ? (double) totalOrders / totalCustomers : 0;
        
        return new CustomerStats(totalCustomers, totalOrders, avgOrdersPerCustomer);
    }
    
    /**
     * Statistics class
     */
    public static class CustomerStats {
        public long totalCustomers;
        public long totalOrders;
        public double avgOrdersPerCustomer;
        
        public CustomerStats(long totalCustomers, long totalOrders, double avgOrdersPerCustomer) {
            this.totalCustomers = totalCustomers;
            this.totalOrders = totalOrders;
            this.avgOrdersPerCustomer = avgOrdersPerCustomer;
        }
    }

}
