package com.banka1.banking.dto;

import com.banka1.banking.dto.interbank.VoteDTO;
import com.banka1.banking.models.Event;
import com.banka1.banking.models.helper.DeliveryStatus;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class CreateEventDeliveryDTO {
    private Event event;

    private DeliveryStatus status;

    private int httpStatus;

    private VoteDTO responseBody;

    private long durationMs;
}
