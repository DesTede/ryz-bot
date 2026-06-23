package com.example.yanivbot.Services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GeoCodingService {

    private static final Logger logger = LoggerFactory.getLogger(GeoCodingService.class);


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

            String formattedAddress = (String) ((Map) results.get(0)).get("formatted_address");
            logger.info("geocode('{}') -> formatted_address='{}', lat={}, lng={}", address, formattedAddress, lat, lng);
            
            return new double[]{lat, lng};

        } catch (Exception e) {
            return null;
        }
    }

    // new method, inserted right after geocode()
    public double[] geocodeByPlaceId(String placeId) {
        try {
            String url = "https://maps.googleapis.com/maps/api/geocode/json?place_id="
                    + placeId + "&language=he&key=" + apiKey;

            Map response = restTemplate.getForObject(url, Map.class);

            List results = (List) response.get("results");
            if (results == null || results.isEmpty()) return null;

            Map location = (Map)((Map)((Map) results.get(0)).get("geometry")).get("location");

            double lat = ((Number) location.get("lat")).doubleValue();
            double lng = ((Number) location.get("lng")).doubleValue();

            String formattedAddress = (String) ((Map) results.get(0)).get("formatted_address");
            logger.info("geocodeByPlaceId('{}') -> formatted_address='{}', lat={}, lng={}", placeId, formattedAddress, lat, lng);

            return new double[]{lat, lng};

        } catch (Exception e) {
            logger.warn("geocodeByPlaceId failed for placeId={}", placeId, e);
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

    public TripInfo getTripInfoByCoords(double originLat, double originLng, double destLat, double destLng) {
        try {
            String url = "https://maps.googleapis.com/maps/api/distancematrix/json?origins="
                    + originLat + "," + originLng
                    + "&destinations=" + destLat + "," + destLng
                    + "&mode=driving&language=he&region=il&key=" + apiKey;

            Map<?, ?> response = restTemplate.getForObject(url, Map.class);

            if (response == null || !"OK".equals(response.get("status"))) return null;

            List<?> rows = (List<?>) response.get("rows");
            if (rows == null || rows.isEmpty()) return null;

            List<?> elements = (List<?>) ((Map<?, ?>) rows.get(0)).get("elements");
            if (elements == null || elements.isEmpty()) return null;

            Map<?, ?> element = (Map<?, ?>) elements.get(0);
            if (!"OK".equals(element.get("status"))) return null;

            Map<?, ?> distanceMap = (Map<?, ?>) element.get("distance");
            Map<?, ?> durationMap = (Map<?, ?>) element.get("duration");
            if (distanceMap == null || durationMap == null) return null;

            double distanceKm = ((Number) distanceMap.get("value")).doubleValue() / 1000.0;
            double durationMinutes = ((Number) durationMap.get("value")).doubleValue() / 60.0;
            return new TripInfo(distanceKm, durationMinutes);

        } catch (Exception e) {
            return null;
        }
    }

    public Double getRouteDurationMinutes(List<double[]> stops) {
        try {
            if (stops == null || stops.size() < 2) return null;

            double[] origin = stops.get(0);
            double[] destination = stops.get(stops.size() - 1);
            List<double[]> intermediates = stops.subList(1, stops.size() - 1);

            // Build origin
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("origin", Map.of("location", Map.of("latLng",
                    Map.of("latitude", origin[0], "longitude", origin[1]))));

            // Build destination
            requestBody.put("destination", Map.of("location", Map.of("latLng",
                    Map.of("latitude", destination[0], "longitude", destination[1]))));

            // Build intermediates with waypoint optimization
            if (!intermediates.isEmpty()) {
                List<Map<String, Object>> intermediatesList = new ArrayList<>();
                for (double[] stop : intermediates) {
                    intermediatesList.add(Map.of("location", Map.of("latLng",
                            Map.of("latitude", stop[0], "longitude", stop[1]))));
                }
                requestBody.put("intermediates", intermediatesList);
                requestBody.put("optimizeWaypointOrder", true);
            }

            requestBody.put("travelMode", "DRIVE");

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("X-Goog-Api-Key", apiKey);
            headers.set("X-Goog-FieldMask", "routes.duration");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            Map<?, ?> response = restTemplate.postForObject(
                    "https://routes.googleapis.com/directions/v2:computeRoutes", entity, Map.class);

            if (response == null) return null;

            List<?> routes = (List<?>) response.get("routes");
            if (routes == null || routes.isEmpty()) return null;

            String durationStr = (String) ((Map<?, ?>) routes.get(0)).get("duration");
            if (durationStr == null) return null;

            // Response is protobuf Duration string e.g. "522s"
            double seconds = Double.parseDouble(durationStr.replace("s", ""));
            return seconds / 60.0;

        } catch (Exception e) {
            return null;
        }
    }

    public OptimizedRoute getOptimizedRoute(List<double[]> stops) {
        try {
            if (stops == null || stops.size() < 2) return null;

            double[] origin = stops.get(0);
            List<double[]> waypoints = stops.subList(1, stops.size());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("origin", Map.of("location", Map.of("latLng",
                    Map.of("latitude", origin[0], "longitude", origin[1]))));

            double[] destination = waypoints.get(waypoints.size() - 1);
            requestBody.put("destination", Map.of("location", Map.of("latLng",
                    Map.of("latitude", destination[0], "longitude", destination[1]))));

            if (waypoints.size() > 1) {
                List<Map<String, Object>> intermediatesList = new ArrayList<>();
                for (int i = 0; i < waypoints.size() - 1; i++) {
                    double[] stop = waypoints.get(i);
                    intermediatesList.add(Map.of("location", Map.of("latLng",
                            Map.of("latitude", stop[0], "longitude", stop[1]))));
                }
                requestBody.put("intermediates", intermediatesList);
                requestBody.put("optimizeWaypointOrder", true);
            }

            requestBody.put("travelMode", "DRIVE");

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("X-Goog-Api-Key", apiKey);
            headers.set("X-Goog-FieldMask", "routes.duration,routes.optimizedIntermediateWaypointIndex");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            Map<?, ?> response = restTemplate.postForObject(
                    "https://routes.googleapis.com/directions/v2:computeRoutes", entity, Map.class);

            if (response == null) return null;
            List<?> routes = (List<?>) response.get("routes");
            if (routes == null || routes.isEmpty()) return null;

            Map<?, ?> route = (Map<?, ?>) routes.get(0);
            String durationStr = (String) route.get("duration");
            if (durationStr == null) return null;
            double totalMinutes = Double.parseDouble(durationStr.replace("s", "")) / 60.0;

            List<?> optimizedIndices = (List<?>) route.get("optimizedIntermediateWaypointIndex");

            List<double[]> orderedStops = new ArrayList<>();
            orderedStops.add(origin);
            if (optimizedIndices != null && waypoints.size() > 1) {
                List<double[]> intermediates = new ArrayList<>(waypoints.subList(0, waypoints.size() - 1));
                for (Object idx : optimizedIndices) {
                    orderedStops.add(intermediates.get(((Number) idx).intValue()));
                }
            }
            orderedStops.add(destination);

            return new OptimizedRoute(totalMinutes, orderedStops);

        } catch (Exception e) {
            logger.warn("[ROUTE] getOptimizedRoute failed: {}", e.getMessage());
            return null;
        }
    }

    public static class OptimizedRoute {
        public final double totalMinutes;
        public final List<double[]> orderedStops;

        public OptimizedRoute(double totalMinutes, List<double[]> orderedStops) {
            this.totalMinutes = totalMinutes;
            this.orderedStops = orderedStops;
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

