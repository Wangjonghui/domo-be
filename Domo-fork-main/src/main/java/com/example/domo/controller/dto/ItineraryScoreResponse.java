package com.example.domo.controller.dto;

import com.example.domo.model.Itinerary;
import com.example.domo.model.Leg;
import com.example.domo.model.Place;

import java.util.ArrayList;
import java.util.List;

public class ItineraryScoreResponse {

    private double routeKm;

    private int distanceScore;
    private int benefitScore;
    private int popularScore;
    private int totalScore;

    private List<LegDTO> legs = new ArrayList<>();

    private List<PlaceDTO> places = new ArrayList<>();

    public static class LegDTO {
        public String fromName;
        public String toName;
        public double distanceKm;

        public LegDTO(String fromName, String toName, double distanceKm) {
            this.fromName = fromName;
            this.toName = toName;
            this.distanceKm = distanceKm;
        }
    }

    public static class PlaceDTO {
        public String name;
        public String category;
        public int distanceScore; // 일정 단위에서 동일 값이면 그대로
        public int benefitScore;
        public int popularScore;
        public int totalScore;

        public PlaceDTO(Place p) {
            this.name = p.getName();
            this.category = p.getCategory();
            this.distanceScore = p.getDistanceScore();
            this.benefitScore = p.getBenefitScore();
            this.popularScore = p.getPopularScore();
            this.totalScore = p.getTotalScore();
        }
    }

    public static ItineraryScoreResponse of(Itinerary itin,
                                             int distanceScore,
                                             int benefitScore,
                                             int popularScore,
                                             boolean includePlaceScores) {
        ItineraryScoreResponse res = new ItineraryScoreResponse();
        res.routeKm = itin.getTotalDistanceKm();
        res.distanceScore = distanceScore;
        res.benefitScore = benefitScore;
        res.popularScore = popularScore;
        res.totalScore = distanceScore + benefitScore + popularScore;

        if (itin.getLegs() != null) {
            for (Leg leg : itin.getLegs()) {
                res.legs.add(new LegDTO(
                        leg.getFrom().getName(),
                        leg.getTo().getName(),
                        leg.getDistanceKm()
                ));
            }
        }

        if (includePlaceScores && itin.getSteps() != null) {
            for (Place p : itin.getSteps()) {
                res.places.add(new PlaceDTO(p));
            }
        }
        return res;
    }

    public static ItineraryScoreResponse of(Itinerary itin) {
        return of(itin, 0, 0, 0, true);
    }

    // ===== getters =====
    public double getRouteKm() { return routeKm; }
    public int getDistanceScore() { return distanceScore; }
    public int getBenefitScore() { return benefitScore; }
    public int getPopularScore() { return popularScore; }
    public int getTotalScore() { return totalScore; }
    public List<LegDTO> getLegs() { return legs; }
    public List<PlaceDTO> getPlaces() { return places; }
}
