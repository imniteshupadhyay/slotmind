package com.slotmind.api;

import com.slotmind.domain.*;
import com.slotmind.engine.StrategyLabel;
import com.slotmind.repo.AppointmentRepository;
import com.slotmind.repo.ProviderRepository;
import com.slotmind.repo.SlotRepository;
import com.slotmind.repo.SuggestionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-side projections for the UI: entities joined with provider names and slot times. */
@Component
@Transactional(readOnly = true)
public class Views {

    public record ProviderView(String id, String name, Specialty specialty, ProviderStatus status, boolean bookable) {
        static ProviderView of(Provider p) {
            return new ProviderView(p.getId(), p.getName(), p.getSpecialty(), p.getStatus(), p.isBookable());
        }
    }

    public record SlotView(String id, String providerId, String providerName, LocalDateTime startTime,
                           int durationMinutes, SlotStatus status, String appointmentId, String patientName) {}

    public record SuggestionView(String id, String appointmentId, String patientName, AppointmentType appointmentType,
                                 String recommendedSlotId, LocalDateTime slotStartTime, int slotDurationMinutes,
                                 String slotProviderId, String slotProviderName, String alternateProviderId,
                                 String alternateProviderName, double confidence, String reasoning,
                                 SuggestionStatus status, TriggerReason triggerReason, String strategy,
                                 boolean fallback, String fallbackReason, LocalDateTime createdAt, LocalDateTime decidedAt) {}

    public record AppointmentView(String id, String patientName, AppointmentType appointmentType, AppointmentStatus status,
                                  String providerId, String providerName, ProviderStatus providerStatus, String slotId,
                                  LocalDateTime slotStartTime, int slotDurationMinutes, SlotStatus slotStatus,
                                  SuggestionView pendingSuggestion) {}

    private final ProviderRepository providers;
    private final SlotRepository slots;
    private final AppointmentRepository appointments;
    private final SuggestionRepository suggestions;

    public Views(ProviderRepository providers, SlotRepository slots, AppointmentRepository appointments,
                 SuggestionRepository suggestions) {
        this.providers = providers;
        this.slots = slots;
        this.appointments = appointments;
        this.suggestions = suggestions;
    }

    private record Lookup(Map<String, Provider> providers, Map<String, Slot> slots, Map<String, Appointment> appointments) {}

    private Lookup lookup() {
        return new Lookup(
                providers.findAll().stream().collect(Collectors.toMap(Provider::getId, Function.identity())),
                slots.findAll().stream().collect(Collectors.toMap(Slot::getId, Function.identity())),
                appointments.findAll().stream().collect(Collectors.toMap(Appointment::getId, Function.identity())));
    }

    public List<ProviderView> providers() {
        return providers.findAll(org.springframework.data.domain.Sort.by("id")).stream().map(ProviderView::of).toList();
    }

    public ProviderView provider(String id) {
        return ProviderView.of(providers.findById(id).orElseThrow(() -> new NotFoundException("Provider", id)));
    }

    public List<SlotView> slots(String providerId, SlotStatus status) {
        Lookup l = lookup();
        Map<String, Appointment> bySlot = activeBySlot(l);
        return slots.findAllByOrderByStartTimeAsc().stream()
                .filter(s -> providerId == null || s.getProviderId().equals(providerId))
                .filter(s -> status == null || s.getStatus() == status)
                .map(s -> slot(s, l, bySlot))
                .toList();
    }

    public SlotView slot(String id) {
        Lookup l = lookup();
        Slot s = Optional.ofNullable(l.slots().get(id)).orElseThrow(() -> new NotFoundException("Slot", id));
        return slot(s, l, activeBySlot(l));
    }

    private static Map<String, Appointment> activeBySlot(Lookup l) {
        Map<String, Appointment> bySlot = new HashMap<>();
        l.appointments().values().stream()
                .filter(a -> a.getStatus() != AppointmentStatus.COMPLETED)
                .forEach(a -> bySlot.put(a.getSlotId(), a));
        return bySlot;
    }

    private static SlotView slot(Slot s, Lookup l, Map<String, Appointment> bySlot) {
        Appointment a = bySlot.get(s.getId());
        Provider p = l.providers().get(s.getProviderId());
        return new SlotView(s.getId(), s.getProviderId(), p == null ? null : p.getName(), s.getStartTime(),
                s.getDurationMinutes(), s.getStatus(), a == null ? null : a.getId(), a == null ? null : a.getPatientName());
    }

    public List<AppointmentView> appointments(AppointmentStatus status) {
        Lookup l = lookup();
        Map<String, RescheduleSuggestion> pending = new HashMap<>();
        suggestions.findByStatus(SuggestionStatus.PENDING).forEach(s ->
                pending.merge(s.getAppointmentId(), s, (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b));
        List<Appointment> list = status == null ? appointments.findAllByOrderByCreatedAtAsc()
                : appointments.findByStatusOrderByCreatedAtAsc(status);
        return list.stream().map(a -> appointment(a, l, pending.get(a.getId()))).toList();
    }

    public AppointmentView appointment(String id) {
        Lookup l = lookup();
        Appointment a = Optional.ofNullable(l.appointments().get(id)).orElseThrow(() -> new NotFoundException("Appointment", id));
        RescheduleSuggestion pending = suggestions.findByAppointmentIdAndStatus(id, SuggestionStatus.PENDING).stream()
                .max(Comparator.comparing(RescheduleSuggestion::getCreatedAt)).orElse(null);
        return appointment(a, l, pending);
    }

    public List<SuggestionView> suggestions(SuggestionStatus status) {
        Lookup l = lookup();
        return suggestions.findAllByOrderByCreatedAtDesc().stream()
                .filter(s -> status == null || s.getStatus() == status)
                .map(s -> suggestion(s, l)).toList();
    }

    public SuggestionView suggestion(String id) {
        return suggestion(suggestions.findById(id).orElseThrow(() -> new NotFoundException("Suggestion", id)), lookup());
    }

    private AppointmentView appointment(Appointment a, Lookup l, RescheduleSuggestion pending) {
        Provider p = l.providers().get(a.getProviderId());
        Slot s = l.slots().get(a.getSlotId());
        return new AppointmentView(a.getId(), a.getPatientName(), a.getAppointmentType(), a.getStatus(), a.getProviderId(),
                p == null ? null : p.getName(), p == null ? null : p.getStatus(), a.getSlotId(),
                s == null ? null : s.getStartTime(), s == null ? 0 : s.getDurationMinutes(), s == null ? null : s.getStatus(),
                pending == null ? null : suggestion(pending, l));
    }

    private SuggestionView suggestion(RescheduleSuggestion s, Lookup l) {
        Appointment a = l.appointments().get(s.getAppointmentId());
        Slot slot = l.slots().get(s.getRecommendedSlotId());
        Provider slotProvider = slot == null ? null : l.providers().get(slot.getProviderId());
        Provider alt = s.getAlternateProviderId() == null ? null : l.providers().get(s.getAlternateProviderId());
        return new SuggestionView(s.getId(), s.getAppointmentId(), a == null ? null : a.getPatientName(),
                a == null ? null : a.getAppointmentType(), s.getRecommendedSlotId(),
                slot == null ? null : slot.getStartTime(), slot == null ? 0 : slot.getDurationMinutes(),
                slot == null ? null : slot.getProviderId(), slotProvider == null ? null : slotProvider.getName(),
                s.getAlternateProviderId(), alt == null ? null : alt.getName(), s.getConfidence(), s.getReasoning(),
                s.getStatus(), s.getTriggerReason(), s.getStrategy(), StrategyLabel.isFallback(s.getStrategy()),
                StrategyLabel.fallbackReason(s.getStrategy()), s.getCreatedAt(), s.getDecidedAt());
    }
}
