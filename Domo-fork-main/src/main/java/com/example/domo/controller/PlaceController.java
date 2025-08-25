package com.example.domo.controller;

import com.example.domo.model.Place;
import com.example.domo.service.PopularityService;
import com.example.domo.service.ScoreService;
import com.example.domo.service.SupabaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@RestController
public class PlaceController {
    private final SupabaseService supabaseService;
    private final ScoreService scoreService;
    private final PopularityService popularityService;
    private static final Logger log = LoggerFactory.getLogger(PlaceController.class);

    public PlaceController(SupabaseService supabaseService,
                           ScoreService scoreService,
                           PopularityService popularityService) {
        this.supabaseService = supabaseService;
        this.scoreService = scoreService;
        this.popularityService = popularityService;
    }

    @GetMapping("/api/place")
    public ResponseEntity<?> getPlace(@RequestParam("code") String code,
                                      @RequestParam(value = "include", required = false) String include) {
        var opt = supabaseService.fetchByPlaceId(code);
        if (opt.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", null);
            return ResponseEntity.ok(body);
        }

        Place p = opt.get();
        boolean withMetrics = "metrics".equalsIgnoreCase(include);
        if (withMetrics) {
            try { popularityService.computePopularity(List.of(p), PopularityService.Mode.GEO_DENSITY); } catch (Exception ignored) {}
            try { scoreService.applyScores(List.of(p), null, null); } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(buildPlaceResponse(p, withMetrics));
    }

    @PostMapping("/api/place")
    public ResponseEntity<?> getPlacePost(@RequestBody Map<String,String> body) {
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "code is required"));
        }
        return getPlace(code, null);
    }

    @GetMapping("/api/benefits")
    public Map<String, Object> benefits(
            @RequestParam(required = false) String search,   // "시 군", 예: "서울 강남"
            @RequestParam(defaultValue = "benefit") String sort, // benefit | popular
            @RequestParam(defaultValue = "1") int page
    ) {
        // "서울 강남" -> sido, sigungu
        String[] tok = (search == null ? "" : search.trim()).split("\\s+");
        String sido    = tok.length > 0 && !tok[0].isBlank() ? tok[0] : null;
        String sigungu = tok.length > 1 && !tok[1].isBlank() ? tok[1] : null;

        final int pageSize = 20;
        final int maxTotalPages = 5;                 // 요구사항 최대 5
        final int poolLimit = pageSize * maxTotalPages; // ✅ DB 한 번만: 최대 100개만 미리 당겨옴

        int p = Math.max(1, page);

        // ✅ DB 호출 1회
        List<Place> pool = supabaseService.fetchPlaces(sido, sigungu, poolLimit, 0);

        // 점수 계산(예외는 로그만 남기고 계속)
        try { popularityService.computePopularity(pool, PopularityService.Mode.GEO_DENSITY); }
        catch (Exception e) { log.debug("[benefits] popularity compute skipped: {}", e.getMessage()); }
        try { scoreService.applyScores(pool, null, null); }
        catch (Exception e) { log.debug("[benefits] score compute skipped: {}", e.getMessage()); }

        // 정렬
        var ko = java.text.Collator.getInstance(java.util.Locale.KOREAN);

        if ("popular".equalsIgnoreCase(sort)) {
            // 인기순 → popularity DESC, 동률 시 할인율 DESC, 이름 정렬
            pool.sort(
                    java.util.Comparator.comparingInt(Place::getPopularity).reversed()
                            .thenComparing(java.util.Comparator.comparingInt((Place x) -> nz(x.getDiscountPercent())).reversed())
                            .thenComparing(Place::getName, ko)
            );
        } else {
            // 혜택순 → 할인율 DESC, 동률 시 인기순 DESC, 이름 정렬
            pool.sort(
                    java.util.Comparator.comparingInt((Place x) -> nz(x.getDiscountPercent())).reversed()
                            .thenComparing(java.util.Comparator.comparingInt(Place::getPopularity).reversed())
                            .thenComparing(Place::getName, ko)
            );
        }

        // totalPages = 실제로 끌어온 개수 기준 (최대 5)
        int totalPages = Math.max(1, (int) Math.ceil(pool.size() / (double) pageSize));
        if (totalPages > maxTotalPages) totalPages = maxTotalPages;
        p = Math.max(1, Math.min(p, totalPages));

        // 페이징 슬라이스
        int from = (p - 1) * pageSize;
        int to   = Math.min(from + pageSize, pool.size());
        List<Place> pageItems = (from < to) ? pool.subList(from, to) : java.util.List.of();

        // payload 변환 (엔티티 직접 노출 X)
        var out = new java.util.ArrayList<java.util.Map<String, Object>>(pageItems.size());
        for (Place place : pageItems) out.add(toPlacePayload(place));

        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("items", out);
        data.put("page", p);
        data.put("pageSize", pageSize);
        data.put("totalPages", totalPages);

        return java.util.Map.of("data", data);
    }

    // null -> 0 방어
    private static int nz(Integer v) { return v == null ? 0 : v; }

    // Place -> JSON payload (민감/내부 필드 노출 방지)
    private java.util.Map<String,Object> toPlacePayload(Place p){
        var m = new java.util.LinkedHashMap<String,Object>();
        m.put("placeId", p.getPlaceId());
        m.put("name", p.getName());
        m.put("category", p.getCategory());
        m.put("address", p.getAddress());
        m.put("lat", p.getLat());
        m.put("lng", p.getLng());
        m.put("sido", p.getSido());
        m.put("sigungu", p.getSigungu());
        m.put("discountPercent", p.getDiscountPercent());
        m.put("popularity", p.getPopularity());
        m.put("totalScore", p.getTotalScore());
        m.put("benefit", p.getBenefit());
        return m;
    }

    private Map<String,Object> buildPlaceResponse(Place p, boolean withMetrics) {
        var data = new LinkedHashMap<String,Object>();
        data.put("placeId", p.getPlaceId());
        data.put("name", p.getName());
        data.put("category", p.getCategory());
        data.put("address", p.getAddress());
        data.put("lat", p.getLat());
        data.put("lng", p.getLng());
        data.put("sido", p.getSido());
        data.put("sigungu", p.getSigungu());
        data.put("discountPercent", p.getDiscountPercent());
        data.put("benefit", p.getBenefit());

        if (withMetrics) {
            var metrics = new LinkedHashMap<String,Object>();
            metrics.put("popularity", p.getPopularity());

            var scores = new LinkedHashMap<String,Object>();
            // Place 모델에 해당 getter가 없다면(또는 안 쓰면) 주석 처리하세요.
            scores.put("distance", p.getDistanceScore());
            scores.put("benefit",  p.getBenefitScore());
            scores.put("popular",  p.getPopularScore());
            scores.put("total",    p.getTotalScore());

            metrics.put("scores", scores);
            data.put("metrics", metrics);
        }

        return Map.of("data", data);
    }
}