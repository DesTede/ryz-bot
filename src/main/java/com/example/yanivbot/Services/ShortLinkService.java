package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.ShortLink;
import com.example.yanivbot.Repositories.ShortLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class ShortLinkService {

    private static final Logger logger = LoggerFactory.getLogger(ShortLinkService.class);

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${app.base-url}")
    private String baseUrl;

    private final ShortLinkRepository shortLinkRepo;

    public ShortLinkService(ShortLinkRepository shortLinkRepo) {
        this.shortLinkRepo = shortLinkRepo;
    }

    /**
     * Creates a short link for the given target URL.
     * Returns the full short URL e.g. https://yourdomain.com/go/x7k2mq
     */
    public String createShortLink(String targetUrl) {
        String code = generateUniqueCode();
        ShortLink shortLink = new ShortLink(code, targetUrl);
        shortLinkRepo.save(shortLink);
        logger.debug("Created short link: /go/{} -> {}", code, targetUrl);
        return baseUrl + "/go/" + code;
    }

    /**
     * Resolves a short code to its target URL.
     * Returns null if not found.
     */
    public String resolve(String code) {
        return shortLinkRepo.findByCode(code)
                .map(ShortLink::getTargetUrl)
                .orElse(null);
    }

    private String generateUniqueCode() {
        String code;
        do {
            code = randomCode();
        } while (shortLinkRepo.existsByCode(code));
        return code;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}