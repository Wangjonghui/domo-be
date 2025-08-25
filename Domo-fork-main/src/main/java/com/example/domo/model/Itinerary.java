package com.example.domo.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Itinerary {
    private List<Place> steps = new ArrayList<>();
    private List<Leg>   legs  = new ArrayList<>();
    private double totalDistanceKm; // legs 합계

    public Itinerary() {
    }
    public Itinerary(List<Place> steps) {
        this.steps = (steps == null) ? new ArrayList<>() : new ArrayList<>(steps);
    }
}
