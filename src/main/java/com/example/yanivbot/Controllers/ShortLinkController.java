package com.example.yanivbot.Controllers;

import com.example.yanivbot.Services.ShortLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ShortLinkController {

    private static final Logger logger = LoggerFactory.getLogger(ShortLinkController.class);

    private final ShortLinkService shortLinkService;

    public ShortLinkController(ShortLinkService shortLinkService) {
        this.shortLinkService = shortLinkService;
    }

    /**
     * Redirects /go/{code} to the target URL.
     */
    @GetMapping("/go/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String targetUrl = shortLinkService.resolve(code);

        if (targetUrl == null) {
            logger.warn("Short link not found: {}", code);
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, targetUrl);
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }
}