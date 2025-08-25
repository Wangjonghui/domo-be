package com.example.domo.controller;

import com.example.domo.controller.dto.AdjustItemRequest;
import com.example.domo.controller.dto.PlanRequest;
import com.example.domo.controller.dto.PlanResponse;
import com.example.domo.controller.dto.RemoveItemRequest;
import com.example.domo.service.DraftRevisionService;
import com.example.domo.service.ItineraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ItineraryController {

    private final ItineraryService itineraryService;
    private final DraftRevisionService draftRevisionService;

    @PostMapping("/plan/full")
    public PlanResponse planFull(@RequestBody PlanRequest req) {
        return itineraryService.createPlanFull(req);
    }

    @PostMapping("/recommend/again")
    public ResponseEntity<Map<String, Object>> recommendAgain(@RequestBody PlanRequest req) {
        String placeId = itineraryService.recommendAgain(req);
        Map<String, Object> wrap = new LinkedHashMap<>();
        Map<String, String> inner = new LinkedHashMap<>();
        inner.put("1", placeId);
        wrap.put("data", inner);
        return ResponseEntity.ok(wrap);
    }

    @PostMapping("/plan/adjust-item")
    public PlanResponse adjustItem(@RequestBody AdjustItemRequest req) {

        // 0) 방어 로직: 인덱스/아이템 검사
        if (req.items == null || req.items.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "items is empty");
        }
        if (req.index < 0 || req.index >= req.items.size()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "index out of range");
        }

        boolean hasAnyChange =
                (req.new_place_id != null && !req.new_place_id.isBlank()) ||
                        (req.new_time != null) ||
                        (req.new_note != null) ||
                        (req.new_est_cost != null);
        if (!hasAnyChange) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "no change requested");
        }

        if (req.new_place_id != null && !req.new_place_id.isBlank()) {
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (int i = 0; i < req.items.size(); i++) {
                if (i == req.index) continue; // 자신 제외
                AdjustItemRequest.Item it = req.items.get(i);
                if (it != null && it.place_id != null && !it.place_id.isBlank()) {
                    existing.add(it.place_id);
                }
            }
            if (existing.contains(req.new_place_id)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "Duplicate place in itinerary");
            }
        }

        if (req.getExclude() != null && req.new_place_id != null &&
                req.getExclude().contains(req.new_place_id)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Excluded place cannot be used");
        }

        try {
            draftRevisionService.assertAndBump(req.getDraftId(), req.getRevision());
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception ignore) {
        }

        PlanResponse updated = itineraryService.adjustDraftItem(req);

        return updated;
    }

    @PostMapping("/plan/remove-item")
    public PlanResponse removeDraft(@RequestBody RemoveItemRequest req) {
        return itineraryService.removeDraftItem(req);
    }

    @PostMapping("/plan/full-ids")
    public ResponseEntity<Map<String, Object>> planFullIds(@RequestBody PlanRequest req) {

        // 1) 입력 기본값/검증
        if (req.getUserLat() == null || req.getUserLng() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "userLat/userLng is required"
            ));
        }
        if (req.getStartAt() == null || req.getStartAt().isBlank()) req.setStartAt("09:00");
        if (req.getEndAt()   == null || req.getEndAt().isBlank())   req.setEndAt("21:00");
        if (req.getRadiusKm() == null || req.getRadiusKm() <= 0)    req.setRadiusKm(3.0);
        if (req.getCandidateLimit() == null || req.getCandidateLimit() <= 0) req.setCandidateLimit(30);

        // 2) 서비스 호출 + NPE 방어
        PlanResponse plan;
        try {
            plan = itineraryService.createPlanFull(req);
        } catch (Exception e) {
            // 서비스 내부 오류는 400으로 내려서 프론트가 복구 가능하게
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Failed to create plan: " + e.getMessage()
            ));
        }

        var items = (plan != null && plan.getItems() != null) ? plan.getItems() : java.util.List.<PlanResponse.Item>of();

        // 3) null item / null id 모두 필터링
        var ids = items.stream()
                .filter(java.util.Objects::nonNull)
                .map(PlanResponse.Item::getId)
                .filter(java.util.Objects::nonNull)
                .toList();

        // 4) 응답 구성 (기존 포맷 유지)
        var data = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < ids.size(); i++) data.put(String.valueOf(i + 1), ids.get(i));

        return ResponseEntity.ok(Map.of(
                "data", data,
                "count", ids.size()
        ));
    }

}
