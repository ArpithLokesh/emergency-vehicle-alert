package com.evas.device;

import com.evas.common.GeoPoints;
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

@RestController
@RequestMapping("/api/traffic-signals")
@RequiredArgsConstructor
public class TrafficSignalController {

    private final TrafficSignalRepository trafficSignalRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TrafficSignalResponse create(@Valid @RequestBody TrafficSignalCreateRequest request) {
        TrafficSignal signal = TrafficSignal.builder()
                .code(request.code())
                .location(GeoPoints.fromDto(request.location()))
                .routingKey(request.routingKey())
                .build();
        return TrafficSignalResponse.from(trafficSignalRepository.save(signal));
    }

    @GetMapping
    public List<TrafficSignalResponse> list(@RequestParam(required = false) Boolean active) {
        List<TrafficSignal> signals = active != null
                ? trafficSignalRepository.findByActive(active)
                : trafficSignalRepository.findAll();
        return signals.stream().map(TrafficSignalResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TrafficSignalResponse get(@PathVariable UUID id) {
        return TrafficSignalResponse.from(findOrThrow(id));
    }

    @PatchMapping("/{id}")
    public TrafficSignalResponse update(@PathVariable UUID id, @RequestBody TrafficSignalUpdateRequest request) {
        TrafficSignal signal = findOrThrow(id);
        if (request.code() != null) {
            signal.setCode(request.code());
        }
        if (request.location() != null) {
            signal.setLocation(GeoPoints.fromDto(request.location()));
        }
        if (request.routingKey() != null) {
            signal.setRoutingKey(request.routingKey());
        }
        if (request.active() != null) {
            signal.setActive(request.active());
        }
        return TrafficSignalResponse.from(trafficSignalRepository.save(signal));
    }

    private TrafficSignal findOrThrow(UUID id) {
        return trafficSignalRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("TrafficSignal " + id + " not found"));
    }
}
