package com.example.domo.controller.dto;

import java.util.List;

public class RemoveItemRequest {
    public static class Item {
        public String time;
        public String place_id;
        public String note;
        public Integer est_cost;
    }
    public Double userLat;
    public Double userLng;
    public String date;
    public List<Item> items;  // 현재 draft 일정
    public int index;         // 삭제할 인덱스
}
