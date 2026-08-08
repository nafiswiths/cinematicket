package com.cinemaseat.payment;

import com.cinemaseat.booking.Booking;
import com.cinemaseat.booking.BookingRepository;
import com.cinemaseat.booking.BookingStateService;
import com.cinemaseat.payment.dto.CallbackPayload;
import com.cinemaseat.payment.dto.ChargeRequest;
import com.cinemaseat.payment.dto.ChargeResponse;
import com.cinemaseat.payment.dto.RefundRequest;
import com.cinemaseat.payment.dto.RefundResponse;
import com.cinemaseat.showseat.ShowSeat;
import com.cinemaseat.showseat.ShowSeatRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock ShowSeatRepository showSeatRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentEventRepository paymentEventRepository;
    @Mock OtpCodeRepository otpCodeRepository;
    @Mock GatewayClient gatewayClient;
    @Mock BookingStateService bookingStateService;

    private HmacUtil hmacUtil;
    private ObjectMapper objectMapper;
    private PaymentServiceImpl paymentService;

    private Booking booking;
    private ShowSeat showSeat;

    @BeforeEach
    void setUp() {
        hmacUtil = new HmacUtil("z2p-2026-secret");
        objectMapper = new ObjectMapper();

        paymentService = new PaymentServiceImpl(
                bookingRepository,
                showSeatRepository,
                paymentRepository,
                paymentEventRepository,
                otpCodeRepository,
                gatewayClient,
                bookingStateService,
                hmacUtil,
                objectMapper,
                "http://api:8080/api/payments/callback"
        );

        booking = new Booking();
        booking.setBookingRef("BK-100");
        booking.setShowSeatId(501L);
        booking.setUserId("user-001");

        showSeat = new ShowSeat();
        showSeat.setPrice(new BigDecimal("450"));
    }

    @Test
    void initiatePaymentSuccess() {
        when(bookingRepository.findByBookingRef("BK-100")).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingRef("BK-100")).thenReturn(Optional.empty());
        when(showSeatRepository.findById(501L)).thenReturn(Optional.of(showSeat));
        when(gatewayClient.charge(any(ChargeRequest.class), eq("ik_BK-100"), eq(null), eq(null)))
                .thenReturn(new ChargeResponse("pay_123", "PENDING"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        Payment p = paymentService.initiatePayment("BK-100");

        assertThat(p.getPaymentId()).isEqualTo("pay_123");
        assertThat(p.getBookingRef()).isEqualTo("BK-100");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getAmount()).isEqualTo(450);

        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    void processCallbackSucceeded() {
        CallbackPayload payload = new CallbackPayload("evt_1", "pay_123", "BK-100", "SUCCEEDED", 450, "BDT", "2026-08-08T12:00:00Z");

        when(paymentEventRepository.existsByEventId("evt_1")).thenReturn(false);
        when(bookingRepository.findByBookingRef("BK-100")).thenReturn(Optional.of(booking));
        when(showSeatRepository.findById(501L)).thenReturn(Optional.of(showSeat));

        Payment existingPayment = new Payment();
        existingPayment.setPaymentId("pay_123");
        existingPayment.setBookingRef("BK-100");
        when(paymentRepository.findByPaymentId("pay_123")).thenReturn(Optional.of(existingPayment));

        boolean res = paymentService.processCallback(payload);

        assertThat(res).isTrue();
        verify(paymentEventRepository).save(any(PaymentEvent.class));
        verify(bookingStateService).confirmBooking("BK-100");
        assertThat(existingPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void processCallbackFailed() {
        CallbackPayload payload = new CallbackPayload("evt_2", "pay_123", "BK-100", "FAILED", 450, "BDT", "2026-08-08T12:00:00Z");

        when(paymentEventRepository.existsByEventId("evt_2")).thenReturn(false);
        when(bookingRepository.findByBookingRef("BK-100")).thenReturn(Optional.of(booking));
        when(showSeatRepository.findById(501L)).thenReturn(Optional.of(showSeat));

        Payment existingPayment = new Payment();
        existingPayment.setPaymentId("pay_123");
        when(paymentRepository.findByPaymentId("pay_123")).thenReturn(Optional.of(existingPayment));

        boolean res = paymentService.processCallback(payload);

        assertThat(res).isTrue();
        verify(bookingStateService).failPayment("BK-100");
        assertThat(existingPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void processDuplicateCallbackReturns200WithoutSecondTransition() {
        CallbackPayload payload = new CallbackPayload("evt_1", "pay_123", "BK-100", "SUCCEEDED", 450, "BDT", "2026-08-08T12:00:00Z");

        when(paymentEventRepository.existsByEventId("evt_1")).thenReturn(true);

        boolean res = paymentService.processCallback(payload);

        assertThat(res).isTrue();
        verify(paymentEventRepository, never()).save(any(PaymentEvent.class));
        verify(bookingStateService, never()).confirmBooking(any());
        verify(bookingStateService, never()).failPayment(any());
    }

    @Test
    void processCallbackRaceConditionReconcilesPaymentRow() {
        CallbackPayload payload = new CallbackPayload("evt_race", "pay_race_99", "BK-100", "SUCCEEDED", 450, "BDT", "2026-08-08T12:00:00Z");

        when(paymentEventRepository.existsByEventId("evt_race")).thenReturn(false);
        when(bookingRepository.findByBookingRef("BK-100")).thenReturn(Optional.of(booking));
        when(showSeatRepository.findById(501L)).thenReturn(Optional.of(showSeat));
        when(paymentRepository.findByPaymentId("pay_race_99")).thenReturn(Optional.empty());
        when(paymentRepository.findByBookingRef("BK-100")).thenReturn(Optional.empty());

        boolean res = paymentService.processCallback(payload);

        assertThat(res).isTrue();
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getPaymentId()).isEqualTo("pay_race_99");
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(bookingStateService).confirmBooking("BK-100");
    }

    @Test
    void processCallbackWithHmacVerification() throws Exception {
        CallbackPayload payload = new CallbackPayload("evt_hmac", "pay_hmac", "BK-100", "SUCCEEDED", 450, "BDT", "2026-08-08T12:00:00Z");
        byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
        String signature = hmacUtil.computeSignature(jsonBytes);

        when(paymentEventRepository.existsByEventId("evt_hmac")).thenReturn(false);

        boolean res = paymentService.processCallback(jsonBytes, signature);

        assertThat(res).isTrue();
        verify(paymentEventRepository).save(any(PaymentEvent.class));
    }

    @Test
    void initiateRefundCallsGatewayRefund() {
        Payment p = new Payment();
        p.setPaymentId("pay_123");
        p.setAmount(450);

        when(paymentRepository.findByPaymentId("pay_123")).thenReturn(Optional.of(p));
        when(gatewayClient.refund(any(RefundRequest.class), eq(null)))
                .thenReturn(new RefundResponse("ref_99", "PENDING"));

        RefundResponse resp = paymentService.initiateRefund("pay_123", null);

        assertThat(resp.getRefundId()).isEqualTo("ref_99");
        assertThat(resp.getStatus()).isEqualTo("PENDING");
    }
}
