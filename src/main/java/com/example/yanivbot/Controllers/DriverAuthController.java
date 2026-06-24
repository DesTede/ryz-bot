package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.DriverOtp;
import com.example.yanivbot.Repositories.DriverOtpRepository;
import com.example.yanivbot.Services.ConversationService;
import com.example.yanivbot.Services.DriverService;
import com.example.yanivbot.Services.JwtService;
import com.example.yanivbot.Services.WhatsappService;
import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;
import java.security.SecureRandom;

@RestController
@RequestMapping("/api/auth/driver")
public class DriverAuthController {

    private static final Logger logger = LoggerFactory.getLogger(DriverAuthController.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_OTP_ATTEMPTS = 5;        // lock the code after this many wrong guesses
    private static final int OTP_REQUEST_COOLDOWN_SECONDS = 60;  // min gap between OTP requests per phone

    private final DriverService driverService;
    private final DriverOtpRepository otpRepo;
    private final JwtService jwtService;
    private final WhatsappService whatsappService;
    private final ConversationService convoService;

    public DriverAuthController(DriverService driverService,
                                DriverOtpRepository otpRepo,
                                JwtService jwtService,
                                WhatsappService whatsappService, ConversationService convoService) {
        this.driverService = driverService;
        this.otpRepo = otpRepo;
        this.jwtService = jwtService;
        this.whatsappService = whatsappService;
        this.convoService = convoService;
    }

    @PostMapping("/request-otp")
    public ResponseEntity<Map<String, String>> requestOtp(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        if (phone == null || phone.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Phone is required"));
        }

        Driver driver = driverService.findByPhone(phone);
        if (driver == null) {
            logger.warn("OTP requested for unknown driver phone: {}", PhoneNumberUtil.maskPhoneNumber(phone));
            return ResponseEntity.status(403).body(Map.of("error", "מספר הטלפון אינו רשום כנהג במערכת"));
        }

        // Rate-limit: reject if a code was already issued within the cooldown window
        DriverOtp recent = otpRepo.findTopByPhoneOrderByExpiresAtDesc(phone).orElse(null);
        if (recent != null && recent.getCreatedAt() != null
                && recent.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(OTP_REQUEST_COOLDOWN_SECONDS))) {
            logger.warn("OTP request throttled for {}", PhoneNumberUtil.maskPhoneNumber(phone));
            return ResponseEntity.status(429).body(Map.of("error", "אנא המתן רגע לפני בקשת קוד נוסף"));
        }

        String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        DriverOtp otp = new DriverOtp(phone, code, expiresAt);
        otpRepo.save(otp);

        boolean inWindow = convoService.isWithin24HourWindow(phone);
        String driverMsg = code + " הוא קוד האימות שלך.\n מטעמי אבטחה, אין לשתף את הקוד הזה ";
        if (inWindow) {
            whatsappService.sendSafeText(phone,driverMsg);
            
        } else {
            whatsappService.sendOtpTemplate(phone, code, "driver_otp");
        }
        logger.info("OTP sent to driver {}", PhoneNumberUtil.maskPhoneNumber(phone));
        return ResponseEntity.ok(Map.of("message", "OTP sent"));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<Map<String, String>> verifyOtp(@RequestBody Map<String, String> body) {
        String phone = body.get("phone");
        String code = body.get("code");

        if (phone == null || code == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Phone and code are required"));
        }

        DriverOtp otp = otpRepo.findTopByPhoneOrderByExpiresAtDesc(phone).orElse(null);

        if (otp == null || otp.isUsed()) {
            return ResponseEntity.status(401).body(Map.of("error", "No active OTP found"));
        }

        if (LocalDateTime.now().isAfter(otp.getExpiresAt())) {
            return ResponseEntity.status(401).body(Map.of("error", "OTP expired"));
        }

        if (otp.getAttempts() >= MAX_OTP_ATTEMPTS) {
            otp.setUsed(true);  // burn the code after too many failures
            otpRepo.save(otp);
            logger.warn("OTP locked after too many attempts for {}", PhoneNumberUtil.maskPhoneNumber(phone));
            return ResponseEntity.status(429).body(Map.of("error", "יותר מדי ניסיונות. בקש קוד חדש"));
        }

        if (!otp.getCode().equals(code)) {
            otp.setAttempts(otp.getAttempts() + 1);
            otpRepo.save(otp);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid OTP"));
        }

        otp.setUsed(true);
        otpRepo.save(otp);

        String token = jwtService.generateToken(phone);
        logger.info("Driver {} authenticated successfully", PhoneNumberUtil.maskPhoneNumber(phone));
        return ResponseEntity.ok(Map.of("token", token));
    }

    @PostMapping("/validate")
    public ResponseEntity<Void> validateToken(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(401).build();
        }
        String token = authHeader.substring(7);
        if (!jwtService.isValid(token)) {
            return ResponseEntity.status(401).build();
        }
        String phone = jwtService.extractPhone(token);
        Driver driver = driverService.findByPhone(phone);
        if (driver == null) {
            logger.warn("Token validation failed - driver not found: {}", PhoneNumberUtil.maskPhoneNumber(phone));
            return ResponseEntity.status(404).build();
        }
        logger.info("Token validated for driver {}", PhoneNumberUtil.maskPhoneNumber(phone));
        return ResponseEntity.ok().build();
    }
}