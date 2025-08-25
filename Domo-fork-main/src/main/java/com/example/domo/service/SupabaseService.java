package com.example.domo.service;

import com.example.domo.model.Place;

import java.util.List;
import java.util.Optional;

public interface SupabaseService {
    // 기존 메서드들 (그대로 유지)
    List<Place> fetchPlaces(String sido, String sigungu, int limit, int offset);
    List<Place> fetchPlacesInOrder(List<String> placeIds);
    Optional<Place> fetchByPlaceId(String placeId);
    int countPlaces(String sido, String sigungu);
    Optional<Place> fetchNearestPlace(Double lat, Double lng, double radiusKm);

    // ✅ 추가: 기준 좌표/반경으로 DB에서만 후보 조회 (카테고리 필터 옵션)
    List<Place> fetchPlacesNear(double centerLat, double centerLng, double radiusKm, List<String> categories, int limit);
}
