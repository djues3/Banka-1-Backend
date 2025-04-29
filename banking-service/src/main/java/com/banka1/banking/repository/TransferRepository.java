package com.banka1.banking.repository;

import com.banka1.banking.models.Transfer;
import com.banka1.banking.models.helper.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    List<Transfer> findAllByStatusAndCreatedAtBefore(TransferStatus status,Long createdAt);

    List<Transfer> findAllByFromAccountId_OwnerID(Long ownerId);



    // Add this method for custom insert with ID
    @Modifying
    @Query(value =
            "INSERT INTO transfer (id, from_account_id, to_account_id, amount, receiver, payment_description, " +
            "from_currency_id, to_currency_id, created_at, type, status, note) " +
            "VALUES (:id, :fromAccountId, :toAccountId, :amount, :receiver, :paymentDescription, " +
            ":fromCurrencyId, :toCurrencyId, :createdAt, :type, :status, :note)",
            nativeQuery = true)
    void insertTransferWithId(@Param("id") UUID id,
                              @Param("fromAccountId") Long fromAccountId,
                              @Param("toAccountId") Long toAccountId,
                              @Param("amount") Double amount,
                              @Param("receiver") String receiver,
                              @Param("paymentDescription") String paymentDescription,
                              @Param("fromCurrencyId") Long fromCurrencyId,
                              @Param("toCurrencyId") Long toCurrencyId,
                              @Param("createdAt") Long createdAt,
                              @Param("type") String type,
                              @Param("status") String status,
                              @Param("note") String note);

}
