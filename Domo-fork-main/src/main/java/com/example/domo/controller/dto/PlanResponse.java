package com.example.domo.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class PlanResponse {

    private String date;
    private double totalKm;
    private int totalEstCost;
    private String rationale;
    private List<Item> items;

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Item {
        private String time;
        private String id;              // place_id
        private String name;
        private String category;
        private String address;
        private double lat;
        private double lng;
        private double legDistanceKm;   // 이동 거리 (구: legKm)
        private int estCost;            // 예상 비용 (추정치)
        private String note;
    }
}
