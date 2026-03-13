package com.example.yanivbot.Services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

@Service
public class GeoCodingService {
    
    @Value("${google.maps.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public double[] geocode(String address) {
        try {
            String encodedAddress = URLEncoder.encode(address, StandardCharsets.UTF_8);
            String url = "https://maps.googleapis.com/maps/api/geocode/json?address="
                    + encodedAddress + "&key=" + apiKey;

            Map response = restTemplate.getForObject(url, Map.class);

            List results = (List) response.get("results");
            if (results == null || results.isEmpty()) return null;

            Map location = (Map)((Map)((Map) results.get(0)).get("geometry")).get("location");

            double lat = ((Number) location.get("lat")).doubleValue();
            double lng = ((Number) location.get("lng")).doubleValue();

            return new double[]{lat, lng};

        } catch (Exception e) {
            System.err.println("Geocoding failed for address: " + address + " — " + e.getMessage());
            return null;
        }
    }
}

