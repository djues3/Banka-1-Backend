package com.banka1.banking.repository;

import com.banka1.banking.models.OtpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OtpTokenRepository extends JpaRepository<OtpToken,Long> {

    Optional<OtpToken> findByTransferIdAndOtpCode(UUID transferId, String otpCode);

    Optional<OtpToken> findByTransferId(UUID transactionId);
}
