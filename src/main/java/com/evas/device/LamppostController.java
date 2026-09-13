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
@RequestMapping("/api/lampposts")
@RequiredArgsConstructor
public class LamppostController {

    private final LamppostRepository lamppostRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LamppostResponse create(@Valid @RequestBody LamppostCreateRequest request) {
        Lamppost lamppost = Lamppost.builder()
                .code(request.code())
                .location(GeoPoints.fromDto(request.location()))
                .routingKey(request.routingKey())
                .build();
        return LamppostResponse.from(lamppostRepository.save(lamppost));
    }

    @GetMapping
    public List<LamppostResponse> list(@RequestParam(required = false) Boolean active) {
        List<Lamppost> lampposts = active != null
                ? lamppostRepository.findByActive(active)
                : lamppostRepository.findAll();
        return lampposts.stream().map(LamppostResponse::from).toList();
    }

    @GetMapping("/{id}")
    public LamppostResponse get(@PathVariable UUID id) {
        return LamppostResponse.from(findOrThrow(id));
    }

    @PatchMapping("/{id}")
    public LamppostResponse update(@PathVariable UUID id, @RequestBody LamppostUpdateRequest request) {
        Lamppost lamppost = findOrThrow(id);
        if (request.code() != null) {
            lamppost.setCode(request.code());
        }
        if (request.location() != null) {
            lamppost.setLocation(GeoPoints.fromDto(request.location()));
        }
        if (request.routingKey() != null) {
            lamppost.setRoutingKey(request.routingKey());
        }
        if (request.active() != null) {
            lamppost.setActive(request.active());
        }
        return LamppostResponse.from(lamppostRepository.save(lamppost));
    }

    private Lamppost findOrThrow(UUID id) {
        return lamppostRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Lamppost " + id + " not found"));
    }
}
