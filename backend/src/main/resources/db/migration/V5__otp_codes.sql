CREATE TABLE otp_codes (
    id BIGSERIAL PRIMARY KEY,
    booking_ref VARCHAR(32) NOT NULL UNIQUE,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    phone VARCHAR(32) NOT NULL,
    code VARCHAR(16) NOT NULL,
    delivered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_otp_codes_event_id ON otp_codes(event_id);
CREATE INDEX idx_otp_codes_booking_ref ON otp_codes(booking_ref);
