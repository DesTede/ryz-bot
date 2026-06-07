package com.example.yanivbot.Utils;


/**
 * Utility class for handling phone numbers safely.
 * Masks phone numbers in logs to protect user privacy.
 */
public class PhoneNumberUtil {

    /**
     * Mask a phone number for safe logging
     * Format: ********4567 (shows last 4 digits)
     *
     * @param phoneNumber The full phone number to mask
     * @return Masked phone number, e.g., "********4567"
     *
     * Example:
     * - Input: "972541234567"
     * - Output: "********4567"
     */
    public static String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return "UNKNOWN";
        }

        if (phoneNumber.length() < 4) {
            return "*".repeat(phoneNumber.length());
        }

        String lastFourDigits = phoneNumber.substring(phoneNumber.length() - 4);
        return "*".repeat(phoneNumber.length() - 4) + lastFourDigits;
    }

    /**
     * Mask a phone number showing country code and last digits
     * Format: 972*****4567 (country code + masked middle + last 4)
     *
     * @param phoneNumber The full phone number to mask
     * @return Masked phone number, e.g., "972*****4567"
     *
     * Example:
     * - Input: "972541234567"
     * - Output: "972*****4567"
     */
    public static String maskPhoneNumberWithCountryCode(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return "UNKNOWN";
        }

        if (phoneNumber.length() < 8) {
            // If too short, just use basic masking
            return maskPhoneNumber(phoneNumber);
        }

        // Show first 3 chars (country code like "972") and last 4 digits
        String prefix = phoneNumber.substring(0, 3);
        String suffix = phoneNumber.substring(phoneNumber.length() - 4);
        int middleLength = phoneNumber.length() - 7; // 3 prefix + 4 suffix

        return prefix + "*".repeat(middleLength) + suffix;
    }

    /**
     * Get display format for phone number
     * Uses maskPhoneNumber format (e.g., "********4567")
     *
     * @param phoneNumber The phone number to mask
     * @return Masked phone number safe for logs
     */
    public static String getDisplayPhone(String phoneNumber) {
        return maskPhoneNumber(phoneNumber);
    }

    /**
     * Check if a phone number is valid (basic check)
     *
     * @param phoneNumber The phone number to check
     * @return true if phone number looks valid
     */
    public static boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return false;
        }

        // Israeli phone numbers: typically 10 digits or 972 + 9 digits
        return phoneNumber.matches("\\d{10}") || phoneNumber.matches("972\\d{9}");
    }

    public static String normalizePhone(String phone) {
        if (phone == null) return null;
        phone = phone.replaceAll("[^0-9]", "");
        if (phone.startsWith("0")) {
            phone = "972" + phone.substring(1);
        }
        if (!phone.startsWith("972")) {
            phone = "972" + phone;
        }
        return phone;
    }

    /**
     * Convert 972XXXXXXXXX to 0XXXXXXXXX for display in messages
     */
    public static String toLocalFormat(String phone) {
        if (phone == null) return "";
        if (phone.startsWith("972")) return "0" + phone.substring(3);
        return phone;
    }
}
