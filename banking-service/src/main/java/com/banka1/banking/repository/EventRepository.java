package com.banka1.banking.repository;

import com.banka1.banking.dto.interbank.InterbankMessageType;
import com.banka1.banking.models.Event;
import com.banka1.banking.models.helper.DeliveryStatus;
import com.banka1.banking.models.helper.IdempotenceKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    boolean existsByIdempotenceKeyAndMessageType(IdempotenceKey idempotenceKey, InterbankMessageType messageType);
    Optional<Event> findByIdempotenceKey(IdempotenceKey idempotenceKey);

    Optional<Event> findByIdempotenceKeyAndMessageType(IdempotenceKey idempotenceKey, InterbankMessageType messageType);


    @Query(value = "SELECT * FROM event WHERE payload::jsonb -> 'message' -> 'transactionId' ->> 'routingNumber' = :routingNumber " +
                   "AND payload::jsonb -> 'message' -> 'transactionId' ->> 'id' = :transactionId " +
                   "ORDER BY created_at LIMIT 1", nativeQuery = true)
    Optional<Event> findByTransactionIdInPayload(@Param("routingNumber") String routingNumber,
                                                 @Param("transactionId") String transactionId);

    IdempotenceKey id(Long id);

    Optional<Event> findByIdempotenceKeyAndMessageTypeAndStatus(IdempotenceKey idempotenceKey, InterbankMessageType messageType, DeliveryStatus status);
}
