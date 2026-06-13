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

    public Double getDistanceKm(String origin, String destination) {
        try {
            String encodedOrigin = URLEncoder.encode(origin, StandardCharsets.UTF_8);
            String encodedDest = URLEncoder.encode(destination, StandardCharsets.UTF_8);
            String url = "https://maps.googleapis.com/maps/api/distancematrix/json?origins="
                    + encodedOrigin + "&destinations=" + encodedDest
                    + "&mode=driving&language=he&region=il&key=" + apiKey;

            Map<?, ?> response = restTemplate.getForObject(url, Map.class);
            if (response == null || !"OK".equals(response.get("status"))) {
                System.err.println("Distance Matrix API response status: " + (response != null ? response.get("status") : "null"));
                return null;
            }

            
            List<?> rows = (List<?>) response.get("rows");
            if (rows == null || rows.isEmpty()) return null;

            List<?> elements = (List<?>) ((Map<?, ?>) rows.get(0)).get("elements");
            if (elements == null || elements.isEmpty()) return null;

            Map<?, ?> element = (Map<?, ?>) elements.get(0);
            if (!"OK".equals(element.get("status"))) {
                System.err.println("Element status is not OK: " + element.get("status"));
                return null;
            }

            Map<?, ?> distanceMap = (Map<?, ?>) element.get("distance");
            if (distanceMap == null) return null;

            Object valueObj = distanceMap.get("value");
            if (valueObj instanceof Number) {
                double meters = ((Number) valueObj).doubleValue();
                return meters / 1000.0;
            }

            return null;

        } catch (Exception e) {
            System.err.println("Distance Matrix failed: " + origin + " → " + destination + " — " + e.getMessage());
            return null;
        }
    }

}

