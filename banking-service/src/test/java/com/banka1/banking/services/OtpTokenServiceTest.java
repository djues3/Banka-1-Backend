package com.banka1.banking.services;

import com.banka1.banking.models.OtpToken;
import com.banka1.banking.repository.OtpTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OtpTokenServiceTest {

    @Mock
    private OtpTokenRepository otpTokenRepository;

    @InjectMocks
    private OtpTokenService otpTokenService;

    private OtpToken otpToken;

    private final UUID uuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        otpToken = new OtpToken();
        otpToken.setTransferId(uuid);
        otpToken.setOtpCode("123456");
        otpToken.setExpirationTime(System.currentTimeMillis() + (5 * 60 * 1000));
        otpToken.setUsed(false);
    }

    @Test
    void generateOtpShouldSaveOtpAndReturnCode() {
        when(otpTokenRepository.saveAndFlush(any(OtpToken.class))).thenAnswer(invocation -> {
            OtpToken savedOtp = invocation.getArgument(0);
            savedOtp.setId(100L);
            return savedOtp;
        });

        String generatedOtp = otpTokenService.generateOtp(uuid);

        assertNotNull(generatedOtp);
        assertEquals(6, generatedOtp.length());
        verify(otpTokenRepository, times(1)).saveAndFlush(any(OtpToken.class));
    }

    @Test
    void isOtpValidShouldReturnTrueWhenOtpIsValid() {
        when(otpTokenRepository.findByTransferIdAndOtpCode(uuid, "123456")).thenReturn(Optional.of(otpToken));

        boolean isValid = otpTokenService.isOtpValid(uuid, "123456");

        assertTrue(isValid);
    }

    @Test
    void isOtpValidShouldReturnFalseWhenOtpIsUsed() {
        otpToken.setUsed(true);
        when(otpTokenRepository.findByTransferIdAndOtpCode(uuid, "123456")).thenReturn(Optional.of(otpToken));

        boolean isValid = otpTokenService.isOtpValid(uuid, "123456");

        assertFalse(isValid);
    }

    @Test
    void isOtpExpiredShouldReturnTrueWhenOtpIsExpired() {
        otpToken.setExpirationTime(System.currentTimeMillis() - 1000);
        when(otpTokenRepository.findByTransferId(uuid)).thenReturn(Optional.of(otpToken));

        boolean isExpired = otpTokenService.isOtpExpired(uuid);

        assertTrue(isExpired);
    }

    @Test
    void markOtpAsUsedShouldSetOtpToUsed() {
        when(otpTokenRepository.findByTransferIdAndOtpCode(uuid, "123456")).thenReturn(Optional.of(otpToken));

        otpTokenService.markOtpAsUsed(uuid, "123456");

        assertTrue(otpToken.isUsed());
        verify(otpTokenRepository, times(1)).save(otpToken);
    }
}

