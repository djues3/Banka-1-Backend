package com.banka1.banking.repository;

import com.banka1.banking.models.Currency;
import com.banka1.banking.models.Event;
import com.banka1.banking.models.EventDelivery;
import com.banka1.banking.models.helper.CurrencyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventDeliveryRepository extends JpaRepository<EventDelivery, Long> {
    List<EventDelivery> findByEvent(Event event);

    @Query("SELECT ed FROM EventDelivery ed WHERE ed.event = :event ORDER BY ed.sentAt ASC LIMIT 1")
    Optional<EventDelivery> findFirstEventDeliveryByEvent(@Param("event") Event event);

}
