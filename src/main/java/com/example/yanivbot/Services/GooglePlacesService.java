package com.example.yanivbot.Services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class GooglePlacesService {

    private static final Logger logger = LoggerFactory.getLogger(GooglePlacesService.class);

    @Value("${google.maps.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Returns up to 4 place suggestions for a given input text.
     * Biased toward Israel.
     */
    public List<PlaceSuggestion> getSuggestions(String input) {
        try {
            String url = "https://maps.googleapis.com/maps/api/place/autocomplete/json"
                    + "?input=" + java.net.URLEncoder.encode(input, "UTF-8")
                    + "&key=" + apiKey
                    + "&language=he"
                    + "&components=country:il";

            logger.info("Places API request for: {}", input);
            Map response = restTemplate.getForObject(url, Map.class);
            logger.info("Places API response: status={}, error_message={}",
                    response != null ? response.get("status") : "null",
                    response != null ? response.get("error_message") : "null");
            
            List<Map> predictions = (List<Map>) response.get("predictions");

            List<PlaceSuggestion> suggestions = new ArrayList<>();
            if (predictions != null) {
                for (int i = 0; i < Math.min(4, predictions.size()); i++) {
                    Map prediction = predictions.get(i);
                    String placeId = (String) prediction.get("place_id");
                    String description = (String) prediction.get("description");
                    suggestions.add(new PlaceSuggestion(placeId, description));
                }
            }
            return suggestions;
        } catch (Exception e) {
            logger.error("Google Places API error: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public static class PlaceSuggestion {
        public final String placeId;
        public final String description;

        public PlaceSuggestion(String placeId, String description) {
            this.placeId = placeId;
            this.description = description;
        }
    }
}