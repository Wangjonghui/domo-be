package com.example.domo.service;

import com.example.domo.controller.dto.ItineraryScoreResponse;
import com.example.domo.model.Itinerary;
import com.example.domo.model.Place;
import com.example.domo.util.HaversineUtil;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.util.*;

@Service
public class ScoreServiceImpl implements ScoreService {

    private static final double MAX_DISTANCE_KM_FOR_SCORE = 15.0; // 이 거리 넘으면 거리점수 0
    private static final int    MAX_DISTANCE_SCORE        = 40;   // 거리 점수 비중
    private static final int    MAX_BENEFIT_SCORE         = 40;   // 할인 점수 비중
    private static final int    MAX_POPULAR_SCORE         = 20;   // 인기 점수 비중

    private static final Collator KOREAN = Collator.getInstance(Locale.KOREA);

    @Override
    public void applyScores(List<Place> places, Double userLat, Double userLng) {
        if (places == null || places.isEmpty()) return;

        final boolean hasUser = (userLat != null && userLng != null
                && Double.isFinite(userLat) && Double.isFinite(userLng));

        for (Place p : places) {
            if (p == null) continue;

            double distanceKm = p.getDistance();
            if (hasUser) {
                distanceKm = safeDistanceKm(userLat, userLng, p.getLat(), p.getLng());
                if (!Double.isFinite(distanceKm) || distanceKm < 0) distanceKm = 0.0;
                p.setDistance(distanceKm);
            } else {
                if (!Double.isFinite(distanceKm) || distanceKm < 0) p.setDistance(0.0);
                distanceKm = p.getDistance();
            }

            int discount = safeNonNegative(p.getDiscountPercent());
            int popular  = safeNonNegative(p.getPopularity());

            int distanceScore = distanceScore(distanceKm);
            int benefitScore  = scaleLinear(discount, 100, MAX_BENEFIT_SCORE);
            int popularScore  = scaleLinear(popular, 100, MAX_POPULAR_SCORE);

            p.updateScores(
                    clamp(distanceScore, 0, MAX_DISTANCE_SCORE),
                    clamp(benefitScore,  0, MAX_BENEFIT_SCORE),
                    clamp(popularScore,  0, MAX_POPULAR_SCORE)
            );
        }
    }

    @Override
    public Comparator<Place> sortBy(String key) {
        String k = (key == null ? "total" : key.trim().toLowerCase(Locale.ROOT));

        Comparator<Place> byName = Comparator.comparing(
                p -> nvl(p.getName(), ""), KOREAN);

        Comparator<Place> byNear = Comparator.comparingDouble(p -> safeFinite(p.getDistance()));

        Comparator<Place> byTotal   = Comparator.comparingInt(Place::getTotalScore).reversed();
        Comparator<Place> byBenefit = Comparator.comparingInt(Place::getBenefitScore).reversed();
        Comparator<Place> byPopular = Comparator.comparingInt(Place::getPopularScore).reversed();
        Comparator<Place> byDiscount= Comparator.comparingInt(Place::getDiscountPercent).reversed();

        return switch (k) {
            case "near", "distance" -> byNear.thenComparing(byName);
            case "benefit"          -> byBenefit.thenComparing(byDiscount).thenComparing(byName);
            case "popular"          -> byPopular.thenComparing(byBenefit).thenComparing(byName);
            case "discount"         -> byDiscount.thenComparing(byPopular).thenComparing(byName);
            default                 -> byTotal.thenComparing(byBenefit).thenComparing(byPopular).thenComparing(byName);
        };
    }

    @Override
    public int calcDistanceScore(double totalDistanceKm) {
        return distanceScore(totalDistanceKm);
    }

    @Override
    public int distanceScore(double routeKm) {
        // 0km일 때 MAX, MAX_DISTANCE_KM_FOR_SCORE 이상은 0
        if (!Double.isFinite(routeKm) || routeKm <= 0) return MAX_DISTANCE_SCORE;
        if (routeKm >= MAX_DISTANCE_KM_FOR_SCORE) return 0;

        double ratio = (MAX_DISTANCE_KM_FOR_SCORE - routeKm) / MAX_DISTANCE_KM_FOR_SCORE;
        int score = (int) Math.round(ratio * MAX_DISTANCE_SCORE);
        return clamp(score, 0, MAX_DISTANCE_SCORE);
    }

    @Override
    public void updatePlaceScores(Itinerary itinerary) {
        if (itinerary == null || itinerary.getSteps() == null) return;

        List<Place> steps = itinerary.getSteps();
        double sumKm = 0.0;
        for (int i = 1; i < steps.size(); i++) {
            Place a = steps.get(i - 1);
            Place b = steps.get(i);
            sumKm += safeDistanceKm(a.getLat(), a.getLng(), b.getLat(), b.getLng());
        }
        itinerary.setTotalDistanceKm(sumKm);

        int dScore = distanceScore(sumKm);
        for (Place p : steps) {
            int benefit = safeNonNegative(p.getDiscountPercent());
            int popular = safeNonNegative(p.getPopularity());
            int benefitScore  = scaleLinear(benefit, 100, MAX_BENEFIT_SCORE);
            int popularScore  = scaleLinear(popular, 100, MAX_POPULAR_SCORE);
            p.updateScores(dScore,
                    clamp(benefitScore, 0, MAX_BENEFIT_SCORE),
                    clamp(popularScore, 0, MAX_POPULAR_SCORE));
        }
    }

    @Override
    public ItineraryScoreResponse buildResponse(Itinerary itin, boolean includePlaceScores) {

        ItineraryScoreResponse resp = new ItineraryScoreResponse();
        try {
        } catch (Throwable ignore) {
        }
        return resp;
    }

    private static int scaleLinear(int raw, int rawMax, int targetMax) {
        int v = clamp(raw, 0, rawMax);
        return (int) Math.round((v / (double) rawMax) * targetMax);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double safeFinite(double d) {
        return (Double.isFinite(d) ? d : 0.0);
    }

    private static int safeNonNegative(Integer v) {
        if (v == null) return 0;
        return Math.max(0, v);
    }

    private static String nvl(String s, String d) {
        return (s == null ? d : s);
    }

    private static double safeDistanceKm(Double lat1, Double lng1, Double lat2, Double lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) return 0.0;
        if (!Double.isFinite(lat1) || !Double.isFinite(lng1) || !Double.isFinite(lat2) || !Double.isFinite(lng2)) return 0.0;
        try {
            return HaversineUtil.distanceKm(lat1, lng1, lat2, lng2);
        } catch (Throwable ignore) {
            return 0.0;
        }
    }

    @SuppressWarnings("unused")
    private static List<Place> maskPlaceScores(List<Place> src) {
        if (src == null) return List.of();
        List<Place> out = new ArrayList<>(src.size());
        for (Place p : src) {
            if (p == null) continue;
            Place q = new Place();
            q.setPlaceId(p.getPlaceId());
            q.setName(p.getName());
            q.setCategory(p.getCategory());
            q.setAddress(p.getAddress());
            q.setLat(p.getLat());
            q.setLng(p.getLng());
            q.setSido(p.getSido());
            q.setSigungu(p.getSigungu());
            q.updateScores(0, 0, 0);
            out.add(q);
        }
        return out;
    }
}
