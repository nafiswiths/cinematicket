package com.cinemaseat.payment;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public class OtpStatusResponse {

    @JsonProperty("bookingRef")
    private String bookingRef;

    @JsonProperty("phone")
    private String phone;

    @JsonProperty("code")
    private String code;

    @JsonProperty("verified")
    private boolean verified;

    @JsonProperty("attempts")
    private int attempts;

    @JsonProperty("deliveredAt")
    private Instant deliveredAt;

    public OtpStatusResponse() {}

    public OtpStatusResponse(String bookingRef, String phone, String code,
                             boolean verified, int attempts, Instant deliveredAt) {
        this.bookingRef = bookingRef;
        this.phone = phone;
        this.code = code;
        this.verified = verified;
        this.attempts = attempts;
        this.deliveredAt = deliveredAt;
    }

    public String getBookingRef() { return bookingRef; }
    public String getPhone() { return phone; }
    public String getCode() { return code; }
    public boolean isVerified() { return verified; }
    public int getAttempts() { return attempts; }
    public Instant getDeliveredAt() { return deliveredAt; }

    public void setBookingRef(String bookingRef) { this.bookingRef = bookingRef; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setCode(String code) { this.code = code; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
}
