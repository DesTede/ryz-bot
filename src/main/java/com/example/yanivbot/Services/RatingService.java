package com.example.yanivbot.Services;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Entities.Rating;
import com.example.yanivbot.Repositories.DriverRepository;
import com.example.yanivbot.Repositories.RatingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class RatingService {

    private static final Logger logger = LoggerFactory.getLogger(RatingService.class);

    public static final String ORDER_TYPE_TAXI = "TAXI";
    public static final String ORDER_TYPE_DELIVERY = "DELIVERY";

    private final RatingRepository ratingRepo;
    private final DriverRepository driverRepo;

    public RatingService(RatingRepository ratingRepo, DriverRepository driverRepo) {
        this.ratingRepo = ratingRepo;
        this.driverRepo = driverRepo;
    }

    /**
     * Create a rating if one doesn't already exist for this order.
     * Returns the saved Rating, or null if a rating already exists (idempotency).
     */
    @Transactional
    public Rating createRating(String driverPhone, String customerPhone, long orderId,
                               String orderType, int stars, String comment) {
        if (stars < 1 || stars > 5) {
            logger.warn("Rejecting rating with invalid stars={} for order #{}", stars, orderId);
            return null;
        }
        if (ratingRepo.existsByOrderIdAndOrderType(orderId, orderType)) {
            logger.info("Rating already exists for {} order #{} — ignoring duplicate", orderType, orderId);
            return null;
        }

        Rating rating = new Rating(driverPhone, customerPhone, orderId, orderType, stars, comment);
        Rating saved = ratingRepo.save(rating);
        logger.info("Saved rating id={} stars={} driver={} order={}",
                saved.getId(), stars, driverPhone, orderId);

        recomputeDriverAggregate(driverPhone, orderType);
        return saved;
    }

    /**
     * Update an existing rating's comment (used for the optional comment step after low stars).
     */
    @Transactional
    public boolean addComment(long ratingId, String comment) {
        Optional<Rating> opt = ratingRepo.findById(ratingId);
        if (opt.isEmpty()) {
            logger.warn("addComment: rating id={} not found", ratingId);
            return false;
        }
        Rating r = opt.get();
        r.setComment(comment);
        ratingRepo.save(r);
        logger.info("Added comment to rating id={}", ratingId);
        return true;
    }

    /**
     * Recompute and persist the driver's aggregate rating (avg + count) for a given order type.
     * Currently only TAXI is wired; aggregate stored on Driver fields apply across all ratings.
     */
    @Transactional
    public void recomputeDriverAggregate(String driverPhone, String orderType) {
        Driver driver = driverRepo.findByPhone(driverPhone).orElse(null);
        if (driver == null) {
            logger.warn("recomputeDriverAggregate: driver {} not found", driverPhone);
            return;
        }
        Double avg = ratingRepo.averageStarsForDriver(driverPhone, orderType);
        long count = ratingRepo.countForDriver(driverPhone, orderType);

        driver.setRatingAvg(avg);
        driver.setRatingCount((int) count);
        driverRepo.save(driver);
        logger.info("Driver {} aggregate updated: avg={} count={}", driverPhone, avg, count);
    }

    public List<Rating> getRecentForDriver(String driverPhone) {
        return ratingRepo.findTop10ByDriverPhoneOrderByCreatedAtDesc(driverPhone);
    }
}