package com.cinemaseat.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OtpCallbackPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("ref")
    private String ref;

    @JsonProperty("phone")
    private String phone;

    @JsonProperty("code")
    private String code;

    @JsonProperty("timestamp")
    private String timestamp;

    public OtpCallbackPayload() {}

    public String getEventId() { return eventId; }
    public String getRef() { return ref; }
    public String getPhone() { return phone; }
    public String getCode() { return code; }
    public String getTimestamp() { return timestamp; }

    public void setEventId(String eventId) { this.eventId = eventId; }
    public void setRef(String ref) { this.ref = ref; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setCode(String code) { this.code = code; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
