package com.slotmind.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmind.domain.AppointmentStatus;
import com.slotmind.domain.AppointmentType;
import com.slotmind.domain.DomainException;
import com.slotmind.domain.TriggerReason;
import com.slotmind.service.BookingService;
import com.slotmind.service.ReschedulingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/appointments")
public class AppointmentController {

    public record BookRequest(@NotBlank String patientName, @NotNull AppointmentType appointmentType,
                              @NotBlank String providerId, @NotBlank String slotId) {}

    public record StatusRequest(@NotNull AppointmentStatus status) {}

    private static final String NO_SLOT = "No open slot with the right specialty is available for this appointment";

    private final BookingService booking;
    private final ReschedulingService rescheduling;
    private final Views views;
    private final Executor streamExecutor;
    private final ObjectMapper json;

    public AppointmentController(BookingService booking, ReschedulingService rescheduling, Views views,
                                 @Qualifier("streamExecutor") Executor streamExecutor, ObjectMapper json) {
        this.booking = booking;
        this.rescheduling = rescheduling;
        this.views = views;
        this.streamExecutor = streamExecutor;
        this.json = json;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Views.AppointmentView book(@Valid @RequestBody BookRequest req) {
        var appt = booking.book(req.patientName(), req.appointmentType(), req.providerId(), req.slotId());
        return views.appointment(appt.getId());
    }

    @GetMapping
    public List<Views.AppointmentView> list(@RequestParam(required = false) AppointmentStatus status) {
        return views.appointments(status);
    }

    @GetMapping("/{id}")
    public Views.AppointmentView get(@PathVariable String id) {
        return views.appointment(id);
    }

    /** Only COMPLETED can be set directly (enforced by {@code Appointment.changeStatus}). */
    @PatchMapping("/{id}/status")
    public Views.AppointmentView changeStatus(@PathVariable String id, @Valid @RequestBody StatusRequest req) {
        booking.changeAppointmentStatus(id, req.status());
        return views.appointment(id);
    }

    /** On-demand (manual) suggestion, synchronous. Uses the same engine as the agent. */
    @PostMapping("/{id}/suggest")
    public Views.SuggestionView suggest(@PathVariable String id) {
        var proposal = rescheduling.propose(id, TriggerReason.INITIAL)
                .orElseThrow(() -> new DomainException(NO_SLOT));
        return views.suggestion(proposal.suggestion().getId());
    }

    /**
     * Bonus: streams the model's output as it is generated.
     * Events: {@code token} (text chunk), {@code suggestion} (final SuggestionView), {@code error}.
     */
    @PostMapping(value = "/{id}/suggest/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter suggestStream(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(120_000L);
        streamExecutor.execute(() -> {
            try {
                var proposal = rescheduling.propose(id, TriggerReason.INITIAL, chunk -> send(emitter, "token", Map.of("text", chunk)));
                if (proposal.isEmpty()) {
                    send(emitter, "error", Map.of("message", NO_SLOT));
                } else {
                    send(emitter, "suggestion", views.suggestion(proposal.get().suggestion().getId()));
                }
                emitter.complete();
            } catch (Exception e) {
                send(emitter, "error", Map.of("message", String.valueOf(e.getMessage())));
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(json.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ignored) {
            // client went away; generation continues so the suggestion is still saved
        }
    }
}
