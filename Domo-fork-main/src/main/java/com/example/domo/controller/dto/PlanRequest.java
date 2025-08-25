    package com.example.domo.controller.dto;

    import lombok.Data;

    import java.util.List;

    @Data
    public class PlanRequest {
        private String date;
        private Double userLat;
        private Double userLng;
        private List<String> categories;
        private String startAt;
        private String endAt;
        private Double radiusKm;
        private Integer candidateLimit;

        private Integer budgetStart;
        private Integer budgetEnd;

        private List<String> subject;

        private String code;
        private List<String> exclude;
        private String excludePlaceId;     // 이번에 교체하고 싶은 placeId
        private String targetCategory;

    }
