package com.example.domo.controller.dto;

import lombok.Data;

import java.util.List;

@Data
public class AdjustItemRequest {
    public static class Item {
        public String time;
        public String place_id;
        public String note;
        public Integer est_cost;
    }

    public Double userLat;
    public Double userLng;
    public String date;
    public List<Item> items;
    public int index;

    private String draftId;
    private Integer revision;
    private java.util.List<String> exclude;

    public String new_place_id;
    public String new_time;
    public String new_note;
    public Integer new_est_cost;
}
