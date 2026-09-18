package com.evas.trip;

import com.evas.common.GeoPointDto;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TripResponse create(@Valid @RequestBody TripCreateRequest request) {
        Trip trip = tripService.create(request.vehicleId(), request.origin(), request.destination());
        return TripResponse.from(trip);
    }

    @GetMapping
    public List<TripResponse> list(@RequestParam(required = false) TripStatus status) {
        return tripService.list(status).stream().map(TripResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TripResponse get(@PathVariable UUID id) {
        return TripResponse.from(tripService.get(id));
    }

    @PostMapping("/{id}/cancel")
    public TripResponse cancel(@PathVariable UUID id) {
        return TripResponse.from(tripService.cancel(id));
    }

    /**
     * The vehicle's position feed. Returns a minimal receipt of the trip's
     * own state (no device data) — see PositionUpdateResponse.
     */
    @PostMapping("/{id}/position")
    public PositionUpdateResponse updatePosition(
            @PathVariable UUID id, @Valid @RequestBody GeoPointDto position) {
        return PositionUpdateResponse.from(tripService.updatePosition(id, position));
    }
}
