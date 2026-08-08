package com.cinemaseat.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "otp_codes")
public class OtpCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_ref", nullable = false, unique = true, length = 32)
    private String bookingRef;

    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(nullable = false, length = 32)
    private String phone;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(name = "delivered_at", nullable = false, updatable = false)
    private Instant deliveredAt;

    @Column(nullable = false)
    private boolean verified;

    @Column(nullable = false)
    private int attempts;

    @PrePersist
    protected void onCreate() {
        if (deliveredAt == null) {
            deliveredAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public String getBookingRef() { return bookingRef; }
    public String getEventId() { return eventId; }
    public String getPhone() { return phone; }
    public String getCode() { return code; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public boolean isVerified() { return verified; }
    public int getAttempts() { return attempts; }

    public void setBookingRef(String bookingRef) { this.bookingRef = bookingRef; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setCode(String code) { this.code = code; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
}
