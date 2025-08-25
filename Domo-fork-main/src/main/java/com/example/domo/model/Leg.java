package com.example.domo.model;

import lombok.Data;

@Data
public class Leg {
    private Place from;
    private Place to;
    private double distanceKm;

    public Leg(Place from, Place to, double distanceKm) {
        this.from = from;
        this.to = to;
        this.distanceKm = distanceKm;
    }
}
