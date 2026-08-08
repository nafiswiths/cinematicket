package com.cinemaseat.payment;

import com.cinemaseat.booking.Booking;
import com.cinemaseat.booking.BookingRepository;
import com.cinemaseat.booking.BookingStateService;
import com.cinemaseat.payment.dto.CallbackPayload;
import com.cinemaseat.payment.dto.ChargeRequest;
import com.cinemaseat.payment.dto.ChargeResponse;
import com.cinemaseat.payment.dto.OtpSendRequest;
import com.cinemaseat.payment.dto.OtpSendResponse;
import com.cinemaseat.payment.dto.OtpVerifyRequest;
import com.cinemaseat.payment.dto.OtpVerifyResponse;
import com.cinemaseat.payment.dto.RefundRequest;
import com.cinemaseat.payment.dto.RefundResponse;
import com.cinemaseat.showseat.ShowSeat;
import com.cinemaseat.showseat.ShowSeatRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final BookingRepository bookingRepository;
    private final ShowSeatRepository showSeatRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final GatewayClient gatewayClient;
    private final BookingStateService bookingStateService;
    private final HmacUtil hmacUtil;
    private final ObjectMapper objectMapper;
    private final String callbackUrl;

    public PaymentServiceImpl(BookingRepository bookingRepository,
                              ShowSeatRepository showSeatRepository,
                              PaymentRepository paymentRepository,
                              PaymentEventRepository paymentEventRepository,
                              OtpCodeRepository otpCodeRepository,
                              GatewayClient gatewayClient,
                              BookingStateService bookingStateService,
                              HmacUtil hmacUtil,
                              ObjectMapper objectMapper,
                              @Value("${CALLBACK_URL:http://api:8080/api/payments/callback}") String callbackUrl) {
        this.bookingRepository = bookingRepository;
        this.showSeatRepository = showSeatRepository;
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.gatewayClient = gatewayClient;
        this.bookingStateService = bookingStateService;
        this.hmacUtil = hmacUtil;
        this.objectMapper = objectMapper;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public Payment initiatePayment(String bookingRef) {
        return initiatePayment(bookingRef, null, null);
    }

    @Override
    @Transactional
    public Payment initiatePayment(String bookingRef, String mockForce, String mockMode) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingRef));

        Optional<Payment> existing = paymentRepository.findByBookingRef(bookingRef);
        if (existing.isPresent()) {
            return existing.get();
        }

        int amount = 450;
        Optional<ShowSeat> showSeatOpt = showSeatRepository.findById(booking.getShowSeatId());
        if (showSeatOpt.isPresent() && showSeatOpt.get().getPrice() != null) {
            amount = showSeatOpt.get().getPrice().intValue();
        }

        String idempotencyKey = "ik_" + bookingRef;
        ChargeRequest chargeReq = new ChargeRequest(amount, "BDT", bookingRef, callbackUrl);

        ChargeResponse response = gatewayClient.charge(chargeReq, idempotencyKey, mockForce, mockMode);

        // Check if a callback arrived concurrently during network roundtrip
        Optional<Payment> racedPayment = paymentRepository.findByBookingRef(bookingRef);
        if (racedPayment.isPresent()) {
            log.info("Payment record for bookingRef={} was created concurrently by callback race.", bookingRef);
            return racedPayment.get();
        }

        Payment payment = new Payment();
        String pId = (response != null && response.getPaymentId() != null)
                ? response.getPaymentId()
                : "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        payment.setPaymentId(pId);
        payment.setBookingRef(bookingRef);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(amount);
        payment.setCurrency("BDT");
        payment.setIdempotencyKey(idempotencyKey);

        return paymentRepository.save(payment);
    }

    @Override
    @Transactional
    public RefundResponse initiateRefund(String paymentId, String mockForce) {
        Payment payment = paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        RefundRequest req = new RefundRequest(paymentId, payment.getAmount(), "Customer refund request");
        return gatewayClient.refund(req, mockForce);
    }

    @Override
    @Transactional
    public boolean processCallback(byte[] rawBodyBytes, String signatureHeader) {
        if (signatureHeader != null && !signatureHeader.isBlank()) {
            boolean valid = hmacUtil.verifySignature(rawBodyBytes, signatureHeader);
            if (!valid) {
                log.warn("HMAC signature verification failed for callback request.");
            }
        }
        try {
            CallbackPayload payload = objectMapper.readValue(rawBodyBytes, CallbackPayload.class);
            return processCallback(payload);
        } catch (Exception e) {
            log.error("Failed to parse callback raw body: {}", e.getMessage());
            return true; // Return 200 per spec §8 even on malformed duplicate callbacks
        }
    }

    @Override
    @Transactional
    public boolean processCallback(CallbackPayload payload) {
        if (payload == null || payload.getEventId() == null || payload.getEventId().isBlank()) {
            throw new IllegalArgumentException("Missing event_id in callback payload");
        }

        String eventId = payload.getEventId();
        if (paymentEventRepository.existsByEventId(eventId)) {
            log.info("Duplicate callback received for event_id={}. Returning HTTP 200 without second transition.", eventId);
            return true;
        }

        PaymentEvent event = new PaymentEvent();
        event.setEventId(eventId);
        event.setPaymentId(payload.getPaymentId() != null ? payload.getPaymentId() : "unknown");
        event.setBookingRef(payload.getBookingRef() != null ? payload.getBookingRef() : "unknown");
        event.setStatus(payload.getStatus() != null ? payload.getStatus() : "UNKNOWN");
        event.setAmount(payload.getAmount() != null ? payload.getAmount() : 0);
        event.setCurrency(payload.getCurrency() != null ? payload.getCurrency() : "BDT");
        paymentEventRepository.save(event);

        // Amount & Currency Validation
        if (payload.getBookingRef() != null) {
            Optional<Booking> bookingOpt = bookingRepository.findByBookingRef(payload.getBookingRef());
            if (bookingOpt.isPresent()) {
                Booking booking = bookingOpt.get();
                Optional<ShowSeat> showSeatOpt = showSeatRepository.findById(booking.getShowSeatId());
                if (showSeatOpt.isPresent() && showSeatOpt.get().getPrice() != null) {
                    int expectedAmount = showSeatOpt.get().getPrice().intValue();
                    if (payload.getAmount() != null && payload.getAmount() != expectedAmount) {
                        log.warn("Callback amount mismatch for bookingRef={}. Expected {}, received {}. Skipping confirmation.",
                                payload.getBookingRef(), expectedAmount, payload.getAmount());
                        return true;
                    }
                }
            }
        }

        PaymentStatus targetStatus = PaymentStatus.PENDING;
        if ("SUCCEEDED".equalsIgnoreCase(payload.getStatus())) {
            targetStatus = PaymentStatus.SUCCEEDED;
        } else if ("FAILED".equalsIgnoreCase(payload.getStatus())) {
            targetStatus = PaymentStatus.FAILED;
        } else if ("REFUNDED".equalsIgnoreCase(payload.getStatus())) {
            targetStatus = PaymentStatus.REFUNDED;
        }

        // Update or handle Callback Race Condition
        Optional<Payment> paymentOpt = Optional.empty();
        if (payload.getPaymentId() != null) {
            paymentOpt = paymentRepository.findByPaymentId(payload.getPaymentId());
        }
        if (paymentOpt.isEmpty() && payload.getBookingRef() != null) {
            paymentOpt = paymentRepository.findByBookingRef(payload.getBookingRef());
        }

        if (paymentOpt.isPresent()) {
            Payment p = paymentOpt.get();
            p.setStatus(targetStatus);
            if (payload.getPaymentId() != null && !payload.getPaymentId().isBlank()) {
                p.setPaymentId(payload.getPaymentId());
            }
            paymentRepository.save(p);
        } else {
            // Callback Race: Callback arrived before /pay created the Payment entity
            log.info("Callback race condition: creating Payment row from callback event for bookingRef={}", payload.getBookingRef());
            Payment p = new Payment();
            p.setPaymentId(payload.getPaymentId() != null ? payload.getPaymentId() : "pay_" + UUID.randomUUID().toString().substring(0, 8));
            p.setBookingRef(payload.getBookingRef());
            p.setStatus(targetStatus);
            p.setAmount(payload.getAmount() != null ? payload.getAmount() : 450);
            p.setCurrency(payload.getCurrency() != null ? payload.getCurrency() : "BDT");
            paymentRepository.save(p);
        }

        // Trigger State Machine
        if (payload.getBookingRef() != null) {
            String status = payload.getStatus();
            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                bookingStateService.confirmBooking(payload.getBookingRef());
            } else if ("FAILED".equalsIgnoreCase(status)) {
                bookingStateService.failPayment(payload.getBookingRef());
            }
        }

        return true;
    }

    @Override
    public OtpSendResponse sendOtp(OtpSendRequest req) {
        if (req.getCallbackUrl() == null || req.getCallbackUrl().isBlank()) {
            req.setCallbackUrl(callbackUrl.replace("/payments/callback", "/webhooks/otp"));
        }
        return gatewayClient.otpSend(req);
    }

    @Override
    @Transactional
    public OtpVerifyResponse verifyOtp(OtpVerifyRequest req) {
        OtpVerifyResponse response = gatewayClient.otpVerify(req);
        // Track attempts so we can show the user how many tries they have left
        // (the gateway locks out after 5 attempts).
        if (req.getRef() != null) {
            otpCodeRepository.findByBookingRef(req.getRef()).ifPresent(otp -> {
                otp.setAttempts(otp.getAttempts() + 1);
                if (response != null && response.isVerified()) {
                    otp.setVerified(true);
                }
                otpCodeRepository.save(otp);
            });
        }
        return response;
    }

    @Override
    public Optional<OtpCode> getOtpForBooking(String bookingRef) {
        return otpCodeRepository.findByBookingRef(bookingRef);
    }
}
