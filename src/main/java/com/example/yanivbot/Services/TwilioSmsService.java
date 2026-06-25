package com.example.yanivbot.Services;

import com.example.yanivbot.Utils.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Twilio SMS fallback for critical alerts when the WhatsApp Cloud API is
 * unreachable or failing. Uses Twilio's REST API directly (no SDK dependency).
 *
 * Credentials are read from environment variables:
 *   TWILIO_ACCOUNT_SID  -> twilio.account-sid
 *   TWILIO_AUTH_TOKEN   -> twilio.auth-token
 *   TWILIO_SENDER_ID    -> twilio.sender-id  (alphanumeric, e.g. "RYZ")
 *
 * If credentials are not configured, sendSms() logs a warning and returns
 * false, so callers degrade gracefully instead of crashing.
 */
@Service
public class TwilioSmsService {

    private static final Logger logger = LoggerFactory.getLogger(TwilioSmsService.class);

    @Value("${twilio.account-sid:}")
    private String accountSid;

    @Value("${twilio.auth-token:}")
    private String authToken;

    @Value("${twilio.sender-id:}")
    private String senderId;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Send an SMS via Twilio.
     *
     * @param phone   recipient phone (any format; normalized to E.164 here)
     * @param message message body (Hebrew supported)
     * @return true if Twilio accepted the message, false otherwise
     */
    public boolean sendSms(String phone, String message) {
        if (accountSid == null || accountSid.isBlank()
                || authToken == null || authToken.isBlank()
                || senderId == null || senderId.isBlank()) {
            logger.warn("⚠️ Twilio not configured (missing SID/token/sender) - skipping SMS fallback to {}",
                    PhoneNumberUtil.maskPhoneNumber(phone));
            return false;
        }

        try {
            String to = "+" + PhoneNumberUtil.normalizePhone(phone);
            String url = "https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json";

            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(accountSid, authToken);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("To", to);
            form.add("From", senderId);
            form.add("Body", message);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().isError()) {
                logger.error("❌ Twilio SMS error to {}: {}",
                        PhoneNumberUtil.maskPhoneNumber(phone), response.getBody());
                return false;
            }

            logger.info("✅ SMS fallback sent via Twilio to {}", PhoneNumberUtil.maskPhoneNumber(phone));
            return true;

        } catch (Exception e) {
            logger.error("❌ Twilio SMS send failed to {}: {}",
                    PhoneNumberUtil.maskPhoneNumber(phone), e.getMessage(), e);
            return false;
        }
    }
}