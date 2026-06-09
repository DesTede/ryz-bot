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
            // 1. Define the URL template with clean placeholders {variable}
            String url = "https://maps.googleapis.com/maps/api/place/autocomplete/json"
                    + "?input={input}"
                    + "&key={key}"
                    + "&language={language}"
                    + "&components={components}";

            // 2. Put the raw, UNENCODED values into a map
            Map<String, String> params = Map.of(
                    "input", input,            // Pass the raw string "הרצל 1 רחובות" directly
                    "key", apiKey,
                    "language", "he",
                    "components", "country:il"
            );

            logger.info("Places API request for: {}", input);

            // 3. Pass the template URL and the parameter map together
            Map response = restTemplate.getForObject(url, Map.class, params);

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
            logger.error("Google Places API error: {}", e.getMessage(), e);
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