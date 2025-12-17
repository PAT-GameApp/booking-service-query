package com.cognizant.BookingService.kafka.consumer;

import java.util.Optional;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cognizant.BookingService.entity.Booking;
import com.cognizant.BookingService.kafka.event.BookingEvent;
import com.cognizant.BookingService.kafka.event.BookingEventType;
import com.cognizant.BookingService.repository.BookingServiceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookingEventConsumer {

    private final BookingServiceRepository bookingRepository;

    @KafkaListener(
            topics = "${kafka.topic.booking-events:booking-events}",
            groupId = "${spring.kafka.consumer.group-id:booking-query-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handleBookingEvent(BookingEvent event) {
        log.info("Received booking event: {} for bookingId: {}", event.getEventType(), event.getBookingId());

        try {
            switch (event.getEventType()) {
                case BookingEventType.CREATED:
                    handleBookingCreated(event);
                    break;
                case BookingEventType.UPDATED:
                    handleBookingUpdated(event);
                    break;
                case BookingEventType.DELETED:
                    handleBookingDeleted(event);
                    break;
                case BookingEventType.ALLOTTED:
                    handleBookingAllotted(event);
                    break;
                default:
                    log.warn("Unknown event type: {}", event.getEventType());
            }
        } catch (Exception e) {
            log.error("Error processing booking event: {}", e.getMessage(), e);
            // In production, you might want to send this to a dead letter queue
            throw e;
        }
    }

    private void handleBookingCreated(BookingEvent event) {
        log.info("Processing CREATED event for booking: {}", event.getBookingId());
        
        // Check if booking already exists (idempotency)
        if (bookingRepository.existsById(event.getBookingId())) {
            log.warn("Booking {} already exists, skipping create", event.getBookingId());
            return;
        }
        
        Booking booking = mapEventToNewBooking(event);
        bookingRepository.save(booking);
        
        log.info("Successfully synced booking {} to query database", event.getBookingId());
    }

    private void handleBookingUpdated(BookingEvent event) {
        log.info("Processing UPDATED event for booking: {}", event.getBookingId());
        
        Optional<Booking> existingBooking = bookingRepository.findById(event.getBookingId());
        if (existingBooking.isPresent()) {
            Booking booking = existingBooking.get();
            updateBookingFromEvent(booking, event);
            bookingRepository.save(booking);
            log.info("Successfully updated booking {} in query database", event.getBookingId());
        } else {
            // If not found, create it (handle out-of-order events)
            Booking booking = mapEventToNewBooking(event);
            bookingRepository.save(booking);
            log.info("Booking {} not found, created new entry", event.getBookingId());
        }
    }

    private void handleBookingDeleted(BookingEvent event) {
        log.info("Processing DELETED event for booking: {}", event.getBookingId());
        
        if (bookingRepository.existsById(event.getBookingId())) {
            bookingRepository.deleteById(event.getBookingId());
            log.info("Successfully deleted booking {} from query database", event.getBookingId());
        } else {
            log.warn("Booking {} not found in query database for deletion", event.getBookingId());
        }
    }

    private void handleBookingAllotted(BookingEvent event) {
        log.info("Processing ALLOTTED event for booking: {}", event.getBookingId());
        
        Optional<Booking> existingBooking = bookingRepository.findById(event.getBookingId());
        if (existingBooking.isPresent()) {
            Booking booking = existingBooking.get();
            updateBookingFromEvent(booking, event);
            bookingRepository.save(booking);
            log.info("Successfully updated allotment for booking {} in query database", event.getBookingId());
        } else {
            // If not found, create it (handle out-of-order events)
            Booking booking = mapEventToNewBooking(event);
            bookingRepository.save(booking);
            log.info("Booking {} not found for allotment, created new entry", event.getBookingId());
        }
    }

    /**
     * Creates a new Booking entity for the query-side read model.
     *
     * Note: query-side uses the bookingId produced by the command side,
     * so we DO set the ID here.
     */
    private Booking mapEventToNewBooking(BookingEvent event) {
        Booking booking = new Booking();
        booking.setBookingId(event.getBookingId());
        booking.setUserId(event.getUserId());
        booking.setGameId(event.getGameId());
        booking.setPlayerIds(event.getPlayerIds());
        booking.setAllotmentId(event.getAllotmentId());
        booking.setEquipmentId(event.getEquipmentId());
        booking.setLocationId(event.getLocationId());
        booking.setBookingStartTime(event.getBookingStartTime());
        booking.setBookingEndTime(event.getBookingEndTime());
        // Let JPA auditing manage createdAt/modifiedAt on the query side.
        // (Event time is still available in event.getEventTimestamp() if needed.)
        return booking;
    }

    /**
     * Updates an existing Booking entity with event data
     */
    private void updateBookingFromEvent(Booking booking, BookingEvent event) {
        booking.setUserId(event.getUserId());
        booking.setGameId(event.getGameId());
        booking.setPlayerIds(event.getPlayerIds());
        booking.setAllotmentId(event.getAllotmentId());
        booking.setEquipmentId(event.getEquipmentId());
        booking.setLocationId(event.getLocationId());
        booking.setBookingStartTime(event.getBookingStartTime());
        booking.setBookingEndTime(event.getBookingEndTime());
        booking.setModifiedAt(event.getModifiedAt());
    }
}
