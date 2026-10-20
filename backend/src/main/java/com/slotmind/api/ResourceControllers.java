package com.slotmind.api;

import com.slotmind.domain.ProviderStatus;
import com.slotmind.domain.SlotStatus;
import com.slotmind.domain.SuggestionStatus;
import com.slotmind.service.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** Providers, slots and suggestions. Kept together: each is a handful of thin endpoints. */
public final class ResourceControllers {
    private ResourceControllers() {}

    @RestController
    @RequestMapping("/providers")
    public static class ProviderController {
        public record StatusRequest(@NotNull ProviderStatus status) {}

        private final BookingService booking;
        private final Views views;

        public ProviderController(BookingService booking, Views views) {
            this.booking = booking;
            this.views = views;
        }

        @GetMapping
        public List<Views.ProviderView> list() {
            return views.providers();
        }

        /** OFFLINE triggers the agentic loop; returns immediately (202). */
        @PatchMapping("/{id}/status")
        @ResponseStatus(HttpStatus.ACCEPTED)
        public Views.ProviderView changeStatus(@PathVariable String id, @Valid @RequestBody StatusRequest req) {
            booking.changeProviderStatus(id, req.status());
            return views.provider(id);
        }
    }

    @RestController
    @RequestMapping("/slots")
    public static class SlotController {
        public record StatusRequest(@NotNull SlotStatus status) {}

        public record CreateRequest(@NotBlank String providerId, @NotNull LocalDateTime startTime,
                                    @Min(5) int durationMinutes) {}

        private final BookingService booking;
        private final Views views;

        public SlotController(BookingService booking, Views views) {
            this.booking = booking;
            this.views = views;
        }

        @GetMapping
        public List<Views.SlotView> list(@RequestParam(required = false) String providerId,
                                         @RequestParam(required = false) SlotStatus status) {
            return views.slots(providerId, status);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public Views.SlotView create(@Valid @RequestBody CreateRequest req) {
            return views.slot(booking.createSlot(req.providerId(), req.startTime(), req.durationMinutes()).getId());
        }

        /** CANCELLED triggers the agentic loop; returns immediately (202). */
        @PatchMapping("/{id}/status")
        @ResponseStatus(HttpStatus.ACCEPTED)
        public Views.SlotView changeStatus(@PathVariable String id, @Valid @RequestBody StatusRequest req) {
            booking.changeSlotStatus(id, req.status());
            return views.slot(id);
        }
    }

    @RestController
    @RequestMapping("/suggestions")
    public static class SuggestionController {
        public record DecisionRequest(@NotNull SuggestionStatus status) {}

        private final BookingService booking;
        private final Views views;

        public SuggestionController(BookingService booking, Views views) {
            this.booking = booking;
            this.views = views;
        }

        @GetMapping
        public List<Views.SuggestionView> list(@RequestParam(required = false) SuggestionStatus status) {
            return views.suggestions(status);
        }

        /** Accept (atomic rebook) or reject. */
        @PatchMapping("/{id}")
        public Views.SuggestionView decide(@PathVariable String id, @Valid @RequestBody DecisionRequest req) {
            switch (req.status()) {
                case ACCEPTED -> booking.accept(id);
                case REJECTED -> booking.reject(id);
                default -> throw new IllegalArgumentException("status must be ACCEPTED or REJECTED");
            }
            return views.suggestion(id);
        }
    }
}
