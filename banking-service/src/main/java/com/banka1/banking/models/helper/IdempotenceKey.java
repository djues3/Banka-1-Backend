package com.banka1.banking.models.helper;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Setter
@Getter
@Embeddable
@AllArgsConstructor
@AttributeOverrides({
        @AttributeOverride(name = "routingNumber", column = @Column(name = "routing_number")),
        @AttributeOverride(name = "locallyGeneratedKey", column = @Column(name = "locally_generated_key"))
})
@ToString
public class IdempotenceKey {

    private Integer routingNumber;

    @Column(length = 64)
    private String locallyGeneratedKey;

    public IdempotenceKey() {

    }
}