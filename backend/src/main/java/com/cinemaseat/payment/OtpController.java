package com.cinemaseat.payment;

import com.cinemaseat.payment.dto.OtpSendRequest;
import com.cinemaseat.payment.dto.OtpSendResponse;
import com.cinemaseat.payment.dto.OtpVerifyRequest;
import com.cinemaseat.payment.dto.OtpVerifyResponse;
import com.cinemaseat.web.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/otp")
public class OtpController {

    private final PaymentService paymentService;

    public OtpController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/send")
    public ResponseEntity<OtpSendResponse> sendOtp(@RequestBody OtpSendRequest req) {
        OtpSendResponse response = paymentService.sendOtp(req);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify")
    public ResponseEntity<OtpVerifyResponse> verifyOtp(@RequestBody OtpVerifyRequest req) {
        OtpVerifyResponse response = paymentService.verifyOtp(req);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the OTP code the gateway has delivered for the given booking, if any.
     * The frontend polls this after /api/otp/send until a code shows up (the gateway
     * may delay 2-15s and silently drop ~10%). When the code is verified, the same
     * record returns {@code verified: true}.
     */
    @GetMapping("/booking/{bookingRef}")
    public ResponseEntity<?> getOtpForBooking(@PathVariable String bookingRef) {
        Optional<OtpCode> otp = paymentService.getOtpForBooking(bookingRef);
        if (otp.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse("OTP_NOT_DELIVERED",
                            "No OTP has been delivered for this booking yet."));
        }
        OtpCode o = otp.get();
        return ResponseEntity.ok(new OtpStatusResponse(
                o.getBookingRef(),
                o.getPhone(),
                o.getCode(),
                o.isVerified(),
                o.getAttempts(),
                o.getDeliveredAt()
        ));
    }
}
