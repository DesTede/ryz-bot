package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.CarType;
import com.example.yanivbot.Models.DriverType;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class TaxiOrderService {
    
    private final ConversationService convoService;
    private final TaxiOrderRepository taxiOrderRepo;
    private final WhatsappService whatsappService;
    private final DriverService driverService;
    private final GeoCodingService geoCodingService;
    private final CustomerService customerService;

    public TaxiOrderService(ConversationService convoService, TaxiOrderRepository taxiOrderRepo, WhatsappService whatsappService,
                            DriverService driverService, GeoCodingService geoCodingService, CustomerService customerService) {
        this.convoService = convoService;
        this.taxiOrderRepo = taxiOrderRepo;
        this.whatsappService = whatsappService;
        this.driverService = driverService;
        this.geoCodingService = geoCodingService;
        this.customerService = customerService;
    }

    public void createTaxiOrder(String customerPhone, String pickUp, String destination, String notes, CarType carType) {
        // SAVE CUSTOMER
        String customerName = "לקוח";  // Default name, will be replaced by WhatsApp profile name if available
        customerService.saveOrUpdateCustomer(customerPhone, customerName);
        
        TaxiOrder taxiOrder = new TaxiOrder(customerPhone, pickUp, destination, notes);
        taxiOrder.setRequestedCarType(carType);

        taxiOrderRepo.save(taxiOrder);
        System.out.println("Taxi order saved with ID: " + taxiOrder.getId());

        broadcastToDrivers(taxiOrder);
        
        String msg = 
                """
                הודעה ללקוח על הזמנה שנוצרה:
                ✅ ההזמנה אושרה! מחפשים נהג קרוב אליך
                🚗 סוג כלי רכב: %s
                🚕 מאיפה: %s
                🎯 לאן: %s
                """.formatted(carType.getHebrewName(), pickUp, destination);
        
        whatsappService.sendSafeText(customerPhone, msg);
        System.out.println("Sending confirmation to customer: " + customerPhone);
    }
    
    public void broadcastToDrivers(TaxiOrder order) {
        String msg =
                """
        🚕 הזמנת מונית חדשה
        🆔 %d
        🚗 סוג כלי רכב: %s
        📍 מאיפה: %s
        🎯 לאן: %s
        📝 הערות: %s
        
        ללקיחת ההזמנה השב:
        מונית %d
        לסיום הנסיעה שלח: הסתיים %d
        """.formatted(
                        order.getId(),
                        order.getRequestedCarType().getHebrewName(),
                        order.getPickUpLocation(),
                        order.getDestination(),
                        order.getNotes(),
                        order.getId(),
                        order.getId()
                );

        System.out.println("Broadcasting to drivers for order: " + order.getId());

        double[] coords = geoCodingService.geocode(order.getPickUpLocation());
        System.out.println("Geocoding result: " + (coords != null ? coords[0] + "," + coords[1] : "null"));

        String orderDetails = "🚗 סוג כלי רכב: " + order.getRequestedCarType().getHebrewName() + "\n" +
                "📍 מאיפה: " + order.getPickUpLocation() + "\n" +
                "🎯 לאן: " + order.getDestination() + "\n" +
                "📞 לקוח: " + order.getPhone();
        
        if (coords != null) {
            driverService.dispatchToClosestDrivers(DriverType.TAXI, msg, coords[0], coords[1], orderDetails, order.getId(), order.getRequestedCarType());
        } else {
            // fallback to all drivers if geocoding fails
            driverService.dispatchToDrivers(DriverType.TAXI, msg, orderDetails, order.getId(), order.getRequestedCarType());
        }
    }

    
    public String claimTaxiOrder(long orderId, String driverPhone) {

        // Check if driver already has an active taxi order
        TaxiOrder activeOrder = taxiOrderRepo
                .findByDriverPhoneAndStatusIn(driverPhone, List.of(TaxiOrderStatus.TAKEN, TaxiOrderStatus.CONFIRMED))
                .orElse(null);

        if (activeOrder != null)
            return "❌ כבר יש לך נסיעה פעילה #" + activeOrder.getId() + ". סיים אותה לפני שתיקח נסיעה חדשה.";
        TaxiOrder order = taxiOrderRepo.findById(orderId).orElse(null);
        
        if (order == null)
            return null;
        
        if (order.getStatus() != TaxiOrderStatus.CREATED)
            return "❌ הזמנה #" + orderId + " כבר תפוסה על ידי מישהו אחר!";
        
        order.setStatus(TaxiOrderStatus.TAKEN);
        order.setDriverPhone(driverPhone);
        taxiOrderRepo.save(order);

        // notify the customer with Google Maps link and car info
        notifyTaxiCustomer(order);
        
        notifyOtherDrivers(orderId, driverPhone);
        
        
        return "הודעה שנשלחת לנהג" +
                "\n" +
                "✅ נהג!" +
                " הזמנה מספר " + orderId + " שויכה אליך" + 
                "\"\uD83D\uDCDE טלפון לקוח: \" " + order.getPhone();
    }

    public String completeOrder(long orderId, String driverPhone) {
        TaxiOrder order = taxiOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getDriverPhone().equals(driverPhone))
            return "❌ הזמנה זו לא שויכה אליך.";
        if (order.getStatus() != TaxiOrderStatus.TAKEN && order.getStatus() != TaxiOrderStatus.CONFIRMED)
            return "❌ הזמנה #" + orderId + " לא פעילה.";

        order.setStatus(TaxiOrderStatus.COMPLETED);
        taxiOrderRepo.save(order);

        whatsappService.sendSafeText(order.getPhone(),
                "✅ הנסיעה הסתיימה! תודה שנסעת איתנו.");

        return "✅ נסיעה #" + orderId + " סומנה כהושלמה. אתה פנוי לנסיעה חדשה!";
    }
    
    private void notifyTaxiCustomer(TaxiOrder order) {
        Driver driver = driverService.findByPhone(order.getDriverPhone());
        String driverName =  driver != null ? driver.getName() : order.getDriverPhone();
        String driverPhone = order.getDriverPhone();
        
        // Get driver's current location
        double[] driverLocation = driverService.getDriverLocation(driverPhone);
        String locationLink = "";
        if (driverLocation != null && driverLocation.length == 2) {
            locationLink = whatsappService.generateGoogleMapsLink(driverLocation[0], driverLocation[1]);
        }
        
        // Build vehicle info
        String vehicleInfo = "";
        if (driver != null && driver.getCarType() != null && driver.getCarModel() != null && driver.getCarColor() != null) {
            vehicleInfo = String.format("\n🚗 כלי רכב: %s %s (%s)", 
                driver.getCarModel(), 
                driver.getCarType().getHebrewName(),
                driver.getCarColor());
        }
        
        String msg = """
        הודעה שנשלחת ללקוח:
        🚕 המונית בדרך!
        📍 מאיפה: %s
        🎯 לאן: %s
        👤 נהג: %s
        📞 טלפון: %s%s
        """.formatted(
                order.getPickUpLocation(),
                order.getDestination(),
                driverName,
                driverPhone,
                vehicleInfo
        );
        
        if (!locationLink.isEmpty()) {
            msg += "\n🗺️ מיקום הנהג: " + locationLink;
        }

        whatsappService.sendSafeText(order.getPhone(), msg);
    }

    private void notifyOtherDrivers(long orderId, String claimingDriverPhone) {
        String message = "🚫 הזמנה #%d נלקחה".formatted(orderId);
        driverService.getActiveDrivers(DriverType.TAXI).forEach(driver -> {
            if (!driver.getPhone().equals(claimingDriverPhone)) {
                whatsappService.sendSafeText(driver.getPhone(), message);
            }
        });
    }
    
    /**
     * Get driver's current location as a Google Maps link
     * Used when customer sends "מיקום"
     */
    public String getDriverLocation(String customerPhone) {
        // Find active taxi order for this customer
        TaxiOrder activeOrder = taxiOrderRepo
                .findByPhoneAndStatus(customerPhone, TaxiOrderStatus.TAKEN)
                .stream()
                .findFirst()
                .orElse(null);
        
        if (activeOrder == null) {
            return "❌ אין הזמנה פעילה כרגע.";
        }
        
        Driver driver = driverService.findByPhone(activeOrder.getDriverPhone());
        if (driver == null) {
            return "❌ לא ניתן למצוא את הנהג.";
        }
        
        double[] driverLocation = driverService.getDriverLocation(activeOrder.getDriverPhone());
        if (driverLocation == null || driverLocation.length != 2) {
            return "❌ מיקום הנהג לא זמין כרגע.";
        }
        
        String locationLink = whatsappService.generateGoogleMapsLink(driverLocation[0], driverLocation[1]);
        
        return "🗺️ מיקום הנהג: " + locationLink;
    }
}
