package com.banka1.banking.models;

import com.banka1.banking.models.helper.DeliveryStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

@Entity
@Getter
@Setter
@ToString
public class EventDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "event_id")
    private Event event;

    private Instant sentAt;

    @Enumerated(EnumType.STRING)
    private DeliveryStatus status;

    private int httpStatus;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    private long durationMs;
}
