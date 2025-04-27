package com.banka1.banking.models;

import com.banka1.banking.dto.interbank.InterbankMessageType;
import com.banka1.banking.models.helper.DeliveryStatus;
import com.banka1.banking.models.helper.IdempotenceKey;
import com.banka1.banking.models.interbank.EventDirection;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@Entity
@ToString
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private InterbankMessageType messageType;

    @Column(columnDefinition = "TEXT")
    private String payload;

    private String url;

    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL)
    private List<EventDelivery> deliveries = new ArrayList<>();

    @Embedded
    private IdempotenceKey idempotenceKey;

    @Column(unique = true)
    private String uniqueKey;

    @Enumerated(EnumType.STRING)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Enumerated(EnumType.STRING)
    private EventDirection direction = EventDirection.OUTGOING;

    @PostLoad @PrePersist
    private void setUniqueKey() {
        if (idempotenceKey != null) {
            this.uniqueKey = idempotenceKey.getRoutingNumber() + "-" + idempotenceKey.getLocallyGeneratedKey();
        }
    }
}