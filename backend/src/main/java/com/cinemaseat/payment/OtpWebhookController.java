package com.cinemaseat.payment;

import com.cinemaseat.payment.dto.OtpCallbackPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Receives OTP delivery callbacks from the provided gateway.
 *
 * The gateway delivers the code to /api/webhooks/otp after a 2-15s delay.
 * ~10% are silently dropped and ~8% are duplicated — we dedupe by event_id.
 *
 * Per spec: always return 2xx so the gateway does not retry.
 */
@RestController
public class OtpWebhookController {

    private static final Logger log = LoggerFactory.getLogger(OtpWebhookController.class);

    private final OtpCodeRepository otpCodeRepository;
    private final ObjectMapper objectMapper;
    private final HmacUtil hmacUtil;

    public OtpWebhookController(OtpCodeRepository otpCodeRepository,
                                ObjectMapper objectMapper,
                                HmacUtil hmacUtil) {
        this.otpCodeRepository = otpCodeRepository;
        this.objectMapper = objectMapper;
        this.hmacUtil = hmacUtil;
    }

    @PostMapping("/api/webhooks/otp")
    @Transactional
    public ResponseEntity<?> onOtpCallback(
            @RequestBody byte[] rawBodyBytes,
            @RequestHeader(value = "X-Signature", required = false) String signatureHeader
    ) {
        try {
            if (signatureHeader != null && !signatureHeader.isBlank()) {
                if (!hmacUtil.verifySignature(rawBodyBytes, signatureHeader)) {
                    log.warn("OTP callback HMAC signature verification failed.");
                }
            }

            OtpCallbackPayload payload = objectMapper.readValue(rawBodyBytes, OtpCallbackPayload.class);
            if (payload == null || payload.getEventId() == null || payload.getEventId().isBlank()) {
                log.warn("OTP callback missing event_id. Returning 200.");
                return ResponseEntity.ok().build();
            }
            if (payload.getRef() == null || payload.getRef().isBlank()
                    || payload.getCode() == null || payload.getCode().isBlank()) {
                log.warn("OTP callback missing ref or code for event_id={}. Returning 200.",
                        payload.getEventId());
                return ResponseEntity.ok().build();
            }

            if (otpCodeRepository.existsByEventId(payload.getEventId())) {
                log.info("Duplicate OTP callback for event_id={}. Returning 200.", payload.getEventId());
                return ResponseEntity.ok().build();
            }

            Optional<OtpCode> existing = otpCodeRepository.findByBookingRef(payload.getRef());
            OtpCode otpCode = existing.orElseGet(OtpCode::new);
            otpCode.setEventId(payload.getEventId());
            otpCode.setBookingRef(payload.getRef());
            otpCode.setPhone(payload.getPhone() != null ? payload.getPhone() : "");
            otpCode.setCode(payload.getCode());
            otpCodeRepository.save(otpCode);

            log.info("OTP code stored for bookingRef={} event_id={}", payload.getRef(), payload.getEventId());
        } catch (Exception e) {
            log.error("Failed to process OTP callback: {}", e.getMessage());
        }
        // Always 2xx per spec — gateway treats non-2xx as delivery failure.
        return ResponseEntity.ok().build();
    }
}
