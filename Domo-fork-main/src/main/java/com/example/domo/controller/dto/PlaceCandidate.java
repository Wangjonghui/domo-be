package com.example.domo.controller.dto;

public record PlaceCandidate(
        Long id,
        String name,
        String category,
        double lat,
        double lng,
        Integer benefitValue,
        Integer benefitPercent,
        Double popularity,
        Integer minutesNeeded
) {}
