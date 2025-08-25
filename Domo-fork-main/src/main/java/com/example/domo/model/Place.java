package com.example.domo.model;

import lombok.Data;

@Data
public class Place {

    private String placeId;

    private String name;
    private String category;
    private String address;
    private Double lat;
    private Double lng;
    private String sido;
    private String sigungu;
    private double distance;
    private int discountPercent;
    private int popularity;
    private int distanceScore;
    private int benefitScore;
    private int popularScore;
    private int totalScore;
    private String benefit;

    public void updateScores(int distanceScore, int benefitScore, int popularScore) {
        this.distanceScore = distanceScore;
        this.benefitScore = benefitScore;
        this.popularScore = popularScore;
        this.totalScore   = distanceScore + benefitScore + popularScore;
    }
}
