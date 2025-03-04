package com.banka1.banking.services;

import com.banka1.banking.models.OtpToken;
import com.banka1.banking.repository.OtpTokenRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

@Service
public class OtpTokenService {


    private final OtpTokenRepository otpTokenRepository;

    public OtpTokenService(OtpTokenRepository otpTokenRepository) {
        this.otpTokenRepository = otpTokenRepository;
    }

    public String generateOtp(Long transferId) {
        Random random = new Random();
        String otpCode = String.format("%06d", random.nextInt(1000000));

        OtpToken otpToken = new OtpToken();
        otpToken.setOtpCode(otpCode);
        otpToken.setTransferId(transferId);
        otpToken.setExpirationTime(System.currentTimeMillis() + (5*60*1000));
        otpToken.setUsed(false);

        otpTokenRepository.save(otpToken);
        return otpCode;
    }

    public boolean isOtpValid(Long transactionId, String otpCode) {
        Optional<OtpToken> otpTokenOptional = otpTokenRepository.findByTransferIdAndOtpCode(transactionId, otpCode);
        return otpTokenOptional.isPresent() && !otpTokenOptional.get().isUsed();
    }

    public boolean isOtpExpired(Long transactionId) {
        Optional<OtpToken> otpTokenOptional = otpTokenRepository.findByTransferId(transactionId);
        return (otpTokenOptional.isEmpty() || (otpTokenOptional.get().getExpirationTime() < System.currentTimeMillis()));
    }

    public void markOtpAsUsed(Long transactionId, String otpCode) {
        Optional<OtpToken> otpTokenOptional = otpTokenRepository.findByTransferIdAndOtpCode(transactionId, otpCode);
        otpTokenOptional.ifPresent(otp -> {
            otp.setUsed(true);
            otpTokenRepository.save(otp);
        });
    }


}


