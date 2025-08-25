package com.example.domo.service;

import com.example.domo.controller.dto.ItineraryScoreResponse;
import com.example.domo.model.Itinerary;
import com.example.domo.model.Place;

import java.util.Comparator;
import java.util.List;

public interface ScoreService {

    int calcDistanceScore(double totalDistanceKm);
    void updatePlaceScores(Itinerary itinerary);

    void applyScores(List<Place> places, Double userLat, Double userLng);
    Comparator<Place> sortBy(String key);

    ItineraryScoreResponse buildResponse(Itinerary itin, boolean includePlaceScores);
    int distanceScore(double routeKm);

}
