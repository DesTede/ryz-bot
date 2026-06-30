package com.example.yanivbot.Entities;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "ratings",
        indexes = {
                @Index(name = "idx_rating_driver", columnList = "driverPhone"),
                @Index(name = "idx_rating_order", columnList = "orderId,orderType")
        })
@Data
public class Rating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(nullable = false)
    private String driverPhone;

    @Column(nullable = false)
    private String customerPhone;

    @Column(nullable = false)
    private long orderId;

    /** "TAXI" or "DELIVERY" — string for forward-compat without a new enum file. */
    @Column(nullable = false, length = 16)
    private String orderType;

    @Column(nullable = false)
    private int stars;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Rating() {}

    public Rating(String driverPhone, String customerPhone, long orderId,
                  String orderType, int stars, String comment) {
        this.driverPhone = driverPhone;
        this.customerPhone = customerPhone;
        this.orderId = orderId;
        this.orderType = orderType;
        this.stars = stars;
        this.comment = comment;
        this.createdAt = LocalDateTime.now();
    }
}