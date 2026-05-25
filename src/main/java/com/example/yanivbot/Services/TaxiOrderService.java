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
        // Save customer
        customerService.saveOrUpdateCustomer(customerPhone, "לקוח");

        TaxiOrder taxiOrder = new TaxiOrder(customerPhone, pickUp, destination, notes);
        taxiOrder.setRequestedCarType(carType);
        taxiOrderRepo.save(taxiOrder);

        broadcastToDrivers(taxiOrder);
        // Don't send message here - the handler will send it
    }

    public void broadcastToDrivers(TaxiOrder order) {
        String msg = """
        🚖 נסיעה חדשה זמינה עבורך
        🆔 מספר הזמנה: %s
        📍 נקודת איסוף: %s
        🎯 יעד הנסיעה: %s
        📝 פרטים נוספים: %s
        """.formatted(
                order.getId(),
                order.getPickUpLocation(),
                order.getDestination(),
                order.getNotes().isEmpty() ? "אין" : order.getNotes()
        );

        double[] coords = geoCodingService.geocode(order.getPickUpLocation());

        String orderDetails = "🚗 סוג כלי רכב: " + order.getRequestedCarType().getHebrewName() + "\n" +
                "📍 מאיפה: " + order.getPickUpLocation() + "\n" +
                "🎯 לאן: " + order.getDestination() + "\n" +
                "📞 לקוח: " + order.getPhone();

        if (coords != null) {
            driverService.dispatchToClosestDrivers(DriverType.TAXI, msg, coords[0], coords[1], orderDetails, order.getId(), order.getRequestedCarType());
        } else {
            driverService.dispatchToDrivers(DriverType.TAXI, msg, orderDetails, order.getId(), order.getRequestedCarType());
        }
    }


    public String claimTaxiOrder(long orderId, String driverPhone) {
        TaxiOrder activeOrder = taxiOrderRepo
                .findByDriverPhoneAndStatusIn(driverPhone, List.of(TaxiOrderStatus.ASSIGNED, TaxiOrderStatus.CONFIRMED))
                .orElse(null);

        if (activeOrder != null)
            return "⚠️ שים לב\nכבר משויכת אליך נסיעה פעילה #" + activeOrder.getId() + " 🚗\n📍 יש לסיים אותה לפני קבלת נסיעה חדשה";

        TaxiOrder order = taxiOrderRepo.findById(orderId).orElse(null);

        if (order == null)
            return null;

        if (order.getStatus() != TaxiOrderStatus.CREATED)
            return "❌ הזמנה #" + orderId + " כבר תפוסה על ידי מישהו אחר!";

        order.setStatus(TaxiOrderStatus.ASSIGNED);
        order.setDriverPhone(driverPhone);
        taxiOrderRepo.save(order);

        notifyTaxiCustomer(order);
        notifyOtherDrivers(orderId, driverPhone);

        // Send confirmation with interactive button for completion
        String confirmationMsg = "🔥 קיבלת את הנסיעה!\n🆔 " + orderId + "\n📞 טלפון נוסע: " + order.getPhone() + "\n🚗 סע בזהירות 🙌";
        whatsappService.sendInteractiveButtonsSafe(
                driverPhone,
                confirmationMsg,
                new WhatsappService.InteractiveButton("taxi_complete_" + orderId, "✅ נסיעה הסתיימה")
        );

        return null; // Message already sent via button
    }

    public String completeOrder(long orderId, String driverPhone) {
        TaxiOrder order = taxiOrderRepo.findById(orderId).orElse(null);

        if (order == null) return "❌ הזמנה #" + orderId + " לא נמצאה.";
        if (!order.getDriverPhone().equals(driverPhone))
            return "❌ הזמנה זו לא שויכה אליך.";
        if (order.getStatus() != TaxiOrderStatus.ASSIGNED && order.getStatus() != TaxiOrderStatus.CONFIRMED)
            return "❌ הזמנה #" + orderId + " לא פעילה.";

        order.setStatus(TaxiOrderStatus.COMPLETED);
        taxiOrderRepo.save(order);

        whatsappService.sendSafeText(order.getPhone(),
                "✅ הנסיעה הסתיימה בהצלחה\nתודה שבחרת לנסוע ב־Movez 🙌 🚙");

        return "🏁 נסיעה #" + orderId + " הסתיימה\nהמערכת סימנה אותך כפנוי לנסיעה הבאה 👍";
    }

    private void notifyTaxiCustomer(TaxiOrder order) {
        Driver driver = driverService.findByPhone(order.getDriverPhone());
        String driverName = driver != null ? driver.getName() : order.getDriverPhone();
        String driverPhone = order.getDriverPhone();

        double[] driverLocation = driverService.getDriverLocation(driverPhone);
        String locationLink = "";
        if (driverLocation != null && driverLocation.length == 2) {
            locationLink = whatsappService.generateGoogleMapsLink(driverLocation[0], driverLocation[1]);
        }

        String vehicleInfo = "";
        if (driver != null && driver.getCarType() != null && driver.getCarModel() != null && driver.getCarColor() != null) {
            vehicleInfo = String.format("\n🚘 פרטי הרכב:\n%s • %s • %s",
                    driver.getCarModel(),
                    driver.getCarType().getHebrewName(),
                    driver.getCarColor());
        }

        String msg = """
        ✅ הנהג בדרכו אליך 
        👤 שם הנהג: %s
        📞 טלפון: %s%s
        📍 איסוף: %s
        🎯 יעד: %s
        """.formatted(
                driverName,
                driverPhone,
                vehicleInfo,
                order.getPickUpLocation(),
                order.getDestination()
        );

        if (!locationLink.isEmpty()) {
            msg += "🗺️ צפייה במיקום הנהג:\n" + locationLink;
        }

        whatsappService.sendSafeText(order.getPhone(), msg);
    }

    private void notifyOtherDrivers(long orderId, String claimingDriverPhone) {
        // Normalize the claiming driver's phone
        String normalizedClaimingPhone = claimingDriverPhone;
        if (normalizedClaimingPhone.startsWith("972")) {
            normalizedClaimingPhone = normalizedClaimingPhone.substring(3);
        }
        final String finalClaimingPhone = normalizedClaimingPhone;

        String message = "🚫 נסיעה #" + orderId + " כבר שויכה לנהג אחר\nהישאר זמין — הזמנה חדשה יכולה להגיע בכל רגע 🚀";
        driverService.getActiveDrivers(DriverType.TAXI).forEach(driver -> {
            if (!driver.getPhone().equals(finalClaimingPhone)) {
                whatsappService.sendSafeText(driver.getPhone(), message);
            }
        });
    }

    public String getDriverLocation(String customerPhone) {
        TaxiOrder activeOrder = taxiOrderRepo
                .findByPhoneAndStatus(customerPhone, TaxiOrderStatus.ASSIGNED)
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
            return "כרגע לא ניתן להציג את מיקום הנהג\n📍 המיקום יתעדכן בקרוב.";
        }

        String locationLink = whatsappService.generateGoogleMapsLink(driverLocation[0], driverLocation[1]);
        return "🗺️ מיקום הנהג: " + locationLink;
    }
}