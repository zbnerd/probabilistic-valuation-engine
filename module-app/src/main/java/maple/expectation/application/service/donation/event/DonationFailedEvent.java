package maple.expectation.application.service.donation.event;

public record DonationFailedEvent(String requestId, String guestUuid, Throwable exception) {}
