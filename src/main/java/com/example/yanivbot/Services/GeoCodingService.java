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
        System.err.println("ORIGIN ARG = " + origin);
        System.err.println("DEST ARG   = " + destination);
        System.err.println("GeoCodingService build: TEST-123");
        
        try {
            if (origin == null || destination == null) {
                System.err.println("Distance Matrix error: origin or destination is null");
                return null;
            }

            String url = "https://maps.googleapis.com/maps/api/distancematrix/json?origins=place_id:"
                    + origin.trim()
                    + "&destinations=place_id:" + destination.trim()
                    + "&mode=driving&language=he&region=il&key=" + apiKey;
            
            System.err.println("Origin sent to Google: " + origin);
            System.err.println("Destination sent to Google: " + destination);
            System.err.println("Distance Matrix URL: " + url);
            
            Map<?, ?> response = restTemplate.getForObject(url, Map.class);

            
            
            System.err.println("Full Distance Matrix response: " + response);
            System.err.println("Distance Matrix API response status: " + (response != null ? response.get("status") : "null"));
            if (response == null || !"OK".equals(response.get("status"))) {
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
                System.err.println("Distance raw value: " + valueObj + " type: " + valueObj.getClass().getName());
                return meters / 1000.0; 
            }

            return null;

        } catch (Exception e) {
            System.err.println("Distance Matrix failed for: " + origin + " -> " + destination + " | Error: " + e.getMessage());
            return null;
        }
    }
}

