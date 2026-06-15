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
            return null;
        }
    }

    public TripInfo getTripInfo(String origin, String destination) {
        try {
            if (origin == null || destination == null) {
                System.err.println("Distance Matrix error: origin or destination is null");
                return null;
            }

            String url = "https://maps.googleapis.com/maps/api/distancematrix/json?origins=place_id:"
                    + origin.trim()
                    + "&destinations=place_id:" + destination.trim()
                    + "&mode=driving&language=he&region=il&key=" + apiKey;
            
            Map<?, ?> response = restTemplate.getForObject(url, Map.class);

            
            
            if (response == null || !"OK".equals(response.get("status"))) {
                return null;
            }

            List<?> rows = (List<?>) response.get("rows");
            if (rows == null || rows.isEmpty()) return null;

            List<?> elements = (List<?>) ((Map<?, ?>) rows.get(0)).get("elements");
            if (elements == null || elements.isEmpty()) return null;

            Map<?, ?> element = (Map<?, ?>) elements.get(0);
            if (!"OK".equals(element.get("status"))) {
                return null;
            }

            Map<?, ?> distanceMap = (Map<?, ?>) element.get("distance");
            if (distanceMap == null) return null;

            Map<?, ?> durationMap = (Map<?, ?>) element.get("duration");
            if (distanceMap == null || durationMap == null) 
                return null;

            Object distanceValue = distanceMap.get("value");
            Object durationValue = durationMap.get("value");

            if (distanceValue instanceof Number && durationValue instanceof Number) {
                double distanceKm = ((Number) distanceValue).doubleValue() / 1000.0;
                double durationMinutes = ((Number) durationValue).doubleValue() / 60.0;
                System.err.println("Distance: " + distanceKm + "km | Duration: " + durationMinutes + "min");
                return new TripInfo(distanceKm, durationMinutes);
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    public static class TripInfo {
        public final double distanceKm;
        public final double durationMinutes;

        public TripInfo(double distanceKm, double durationMinutes) {
            this.distanceKm = distanceKm;
            this.durationMinutes = durationMinutes;
        }
    }
}

