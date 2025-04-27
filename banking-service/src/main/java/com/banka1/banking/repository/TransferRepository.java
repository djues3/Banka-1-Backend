package com.banka1.banking.repository;

import com.banka1.banking.models.Transfer;
import com.banka1.banking.models.helper.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    List<Transfer> findAllByStatusAndCreatedAtBefore(TransferStatus status,Long createdAt);

    List<Transfer> findAllByFromAccountId_OwnerID(Long ownerId);


}
