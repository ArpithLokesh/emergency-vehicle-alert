package com.evas.vehicle;

import com.evas.common.ConflictException;
import com.evas.common.NotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plain CRUD over the vehicle registry — no business logic beyond
 * persistence lives here, so there's no separate service class in between:
 * a pass-through service would just be an extra layer with nothing to do.
 * That changes the moment real rules show up (e.g. "a trip needs an
 * AVAILABLE vehicle", MVP-1) — that logic will live in Trip's service, not
 * here, since it's Trip's rule to enforce, not Vehicle's.
 */
@RestController
@RequestMapping("/api/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleRepository vehicleRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleResponse create(@Valid @RequestBody VehicleCreateRequest request) {
        Vehicle vehicle = Vehicle.builder()
                .callSign(request.callSign())
                .type(request.type())
                .build();
        return VehicleResponse.from(vehicleRepository.save(vehicle));
    }

    @GetMapping
    public List<VehicleResponse> list(
            @RequestParam(required = false) VehicleType type,
            @RequestParam(required = false) VehicleStatus status) {
        List<Vehicle> vehicles;
        if (type != null && status != null) {
            vehicles = vehicleRepository.findByTypeAndStatus(type, status);
        } else if (type != null) {
            vehicles = vehicleRepository.findByType(type);
        } else if (status != null) {
            vehicles = vehicleRepository.findByStatus(status);
        } else {
            vehicles = vehicleRepository.findAll();
        }
        return vehicles.stream().map(VehicleResponse::from).toList();
    }

    @GetMapping("/{id}")
    public VehicleResponse get(@PathVariable UUID id) {
        return VehicleResponse.from(findOrThrow(id));
    }

    @PatchMapping("/{id}")
    public VehicleResponse update(@PathVariable UUID id, @RequestBody VehicleUpdateRequest request) {
        Vehicle vehicle = findOrThrow(id);
        if (request.callSign() != null) {
            vehicle.setCallSign(request.callSign());
        }
        if (request.status() != null && request.status() != vehicle.getStatus()) {
            validateStatusTransition(vehicle.getStatus(), request.status());
            vehicle.setStatus(request.status());
        }
        return VehicleResponse.from(vehicleRepository.save(vehicle));
    }

    private Vehicle findOrThrow(UUID id) {
        return vehicleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Vehicle " + id + " not found"));
    }

    /**
     * PATCH is an admin action on the registry, not the trip lifecycle —
     * ON_TRIP is exclusively TripService's to set (trip creation) and clear
     * (trip termination); letting PATCH touch it would let an admin desync
     * the vehicle from what TripService believes is true (an active or
     * absent trip). Cancel the trip instead of PATCHing around it.
     * RETIRED is terminal: retiring a vehicle is a one-way decision, not a
     * status to recover from via this endpoint.
     */
    private void validateStatusTransition(VehicleStatus from, VehicleStatus to) {
        if (from == VehicleStatus.ON_TRIP || to == VehicleStatus.ON_TRIP) {
            throw new ConflictException(
                    "Cannot change vehicle status to/from ON_TRIP directly; "
                            + "that transition belongs to trip creation/cancellation");
        }
        if (from == VehicleStatus.RETIRED) {
            throw new ConflictException("Vehicle is RETIRED, a terminal status");
        }
    }
}
