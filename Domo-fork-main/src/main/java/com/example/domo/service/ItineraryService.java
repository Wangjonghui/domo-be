package com.example.domo.service;

import com.example.domo.controller.dto.AdjustItemRequest;
import com.example.domo.controller.dto.PlanRequest;
import com.example.domo.controller.dto.PlanResponse;
import com.example.domo.controller.dto.RemoveItemRequest;
import com.example.domo.model.Place;
import com.example.domo.util.HaversineUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ItineraryService {

    private final SupabaseService supabaseService;
    private final GptService gptService;
    private final ObjectMapper om = new ObjectMapper();

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Logger log = LoggerFactory.getLogger(ItineraryService.class);
    private final Set<String> excludeIds = new HashSet<>();

    public ItineraryService(SupabaseService supabaseService, GptService gptService) {
        this.supabaseService = supabaseService;
        this.gptService = gptService;
    }

    /** 주소 좌표 → 반경 내 DB 후보만 → GPT가 후보 중에서만 선택/시간 배치 → 일정 응답 */
    public PlanResponse createPlanFull(PlanRequest req) {
        // 0) 중심 좌표 결정: userLat/Lng → code(placeId) → 기본좌표
        double[] center = resolveCenter(req);
        final double userLat = center[0];
        final double userLng = center[1];

        final String startAt = nz(req.getStartAt(), "10:00");
        final String endAt   = nz(req.getEndAt(),   "18:00");
        double radius        = nz(req.getRadiusKm(), 5.0);
        final int candidateLimit = nz(req.getCandidateLimit(), 300);

        final List<String> categories = (req.getCategories() == null) ? List.of()
                : req.getCategories().stream().filter(Objects::nonNull).map(String::toLowerCase).toList();

        // 1) 반경 자동 확장: DB 쿼리 실패 대비 폴백 포함
        List<Place> pool = new ArrayList<>();
        double[] steps = { radius, 8, 12, 15, 20, 30 };
        for (double r : steps) {
            try {
                pool = supabaseService.fetchPlacesNear(userLat, userLng, r, categories, candidateLimit);
            } catch (Exception ex) {
                // ✅ DB 확장 미설치/SQL 차이/임시장애 등: 자바 폴백(일반 리스트 → 하버사인 필터)
                log.error("[fetchPlacesNear] failed: {}  → fallback to in-memory filter", ex.getMessage());
                List<Place> all = supabaseService.fetchPlaces(null, null, Math.max(candidateLimit, 300), 0);
                pool = filterByRadiusInMemory(all, userLat, userLng, r, categories);
            }
            if (pool.size() >= 20) { radius = r; break; }
        }

        // 1-2) 그래도 비면, 예외 던지지 말고 빈 일정 정상 응답
        if (pool.isEmpty()) {
            return new PlanResponse(today(), 0.0, 0,
                    "주변 DB 후보가 없습니다. 반경/카테고리를 넓혀보세요.", List.of());
        }

        // 2) GPT 입력(candidates는 **오직 DB 후보**)
        String placesJson = toJson(pool.stream().map(p -> Map.of(
                "place_id", nvl(p.getPlaceId()),
                "name",     nvl(p.getName()),
                "category", nvl(p.getCategory()),
                "address",  nvl(p.getAddress()),
                "lat",      nz(p.getLat()),
                "lng",      nz(p.getLng()),
                "discountpercent", Math.max(0, p.getDiscountPercent()),
                "totalscore",      Math.max(0, p.getTotalScore())
        )).collect(Collectors.toList()));

        Map<String, Object> pref = new LinkedHashMap<>();
        pref.put("start_at", startAt);
        pref.put("end_at",   endAt);
        if (!categories.isEmpty()) pref.put("categories", categories);
        if (req.getBudgetStart() != null) pref.put("budget_min", req.getBudgetStart());
        if (req.getBudgetEnd() != null)   pref.put("budget_max", req.getBudgetEnd());
        String userPrefJson = toJson(pref);

        // 3) GPT 호출 → 실패 시 근접순 폴백
        String planJson;
        try {
            planJson = gptService.planOneDayJson("AUTO", placesJson, userPrefJson);
        } catch (Exception e) {
            log.warn("[OpenAI] error: {}  → fallback to nearest ordering", e.getMessage());
            List<Place> alt = pool.stream()
                    .sorted(Comparator.comparingDouble(p ->
                            HaversineUtil.distanceKm(userLat, userLng, nz(p.getLat()), nz(p.getLng()))))
                    .limit(6).toList();
            return buildPlanResponseFromPlaces(alt, userLat, userLng, startAt, endAt,
                    "GPT 장애로 근접순 대체");
        }

        // 4) GPT 결과 파싱 + 화이트리스트 검증(후보 밖 ID는 무시)
        List<GItem> gItems = parseGptItems(planJson);
        if (gItems.isEmpty()) {
            List<Place> alt = pool.stream()
                    .sorted(Comparator.comparingDouble(p ->
                            HaversineUtil.distanceKm(userLat, userLng, nz(p.getLat()), nz(p.getLng()))))
                    .limit(6).toList();
            return buildPlanResponseFromPlaces(alt, userLat, userLng, startAt, endAt,
                    "GPT 빈 응답으로 근접순 대체");
        }

        Map<String, Place> byId = pool.stream()
                .collect(Collectors.toMap(Place::getPlaceId, p -> p, (a,b)->a));
        List<Place> picked = new ArrayList<>();
        List<String> orderIds = new ArrayList<>();
        for (GItem gi : gItems) {
            if (gi.placeId == null || !byId.containsKey(gi.placeId)) continue;
            if (orderIds.contains(gi.placeId)) continue;
            orderIds.add(gi.placeId);
            picked.add(byId.get(gi.placeId));
        }
        if (picked.isEmpty()) {
            List<Place> alt = pool.stream()
                    .sorted(Comparator.comparingDouble(p ->
                            HaversineUtil.distanceKm(userLat, userLng, nz(p.getLat()), nz(p.getLng()))))
                    .limit(6).toList();
            return buildPlanResponseFromPlaces(alt, userLat, userLng, startAt, endAt,
                    "검증 실패로 근접순 대체");
        }

        Map<String, String> timeById = gItems.stream()
                .filter(gi -> gi.placeId != null && gi.time != null && gi.time.matches("\\d{2}:\\d{2}"))
                .collect(Collectors.toMap(gi -> gi.placeId, gi -> gi.time, (a,b)->a, LinkedHashMap::new));

        picked = balanceCategories(picked);
        picked = avoidConsecutiveSameCategory(picked);

        return buildPlanResponseWithTimes(picked, orderIds, userLat, userLng, startAt, endAt,
                timeById, "GPT 추천 일정(후보 화이트리스트 적용)");
    }

    /* ======================== 기존 adjust/remove/recommend 유지 ======================== */

    public String recommendAgain(PlanRequest req) {
        if (req.getUserLat() == null || req.getUserLng() == null)
            throw new IllegalArgumentException("userLat/userLng 가 필요합니다.");

        final double lat = req.getUserLat();
        final double lng = req.getUserLng();

        List<String> cats = (req.getCategories() == null) ? List.of()
                : req.getCategories().stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .toList();

        if (req.getExcludePlaceId() != null) {
            excludeIds.add(req.getExcludePlaceId());
        }

        List<Place> pool = new ArrayList<>();
        for (double r : new double[]{3, 5, 8}) {
            try {
                pool = supabaseService.fetchPlacesNear(lat, lng, r, cats, 200);
            } catch (Exception ex) {
                log.error("[fetchPlacesNear-again] failed: {} → fallback", ex.getMessage());
                List<Place> all = supabaseService.fetchPlaces(null, null, 400, 0);
                pool = filterByRadiusInMemory(all, lat, lng, r, cats);
            }
            if (!pool.isEmpty()) break;
        }

        Set<String> allExcluded = new HashSet<>(excludeIds);
        if (req.getExclude() != null) {
            allExcluded.addAll(req.getExclude().stream()
                    .filter(Objects::nonNull)
                    .toList());
        }

        pool = pool.stream()
                .filter(p -> p.getPlaceId() != null && !allExcluded.contains(p.getPlaceId()))
                .toList();

        if (req.getTargetCategory() != null) {
            String targetCategory = req.getTargetCategory().trim();

            if (targetCategory.equalsIgnoreCase("음식") || targetCategory.equalsIgnoreCase("식당")) {
                targetCategory = "음식점";
            } else if (targetCategory.equalsIgnoreCase("카페")) {
                targetCategory = "카페";
            } else if (targetCategory.equalsIgnoreCase("놀거리") || targetCategory.equalsIgnoreCase("액티비티")) {
                targetCategory = "놀거리";
            }

            final String finalCat = targetCategory;
            pool = pool.stream()
                    .filter(p -> finalCat.equalsIgnoreCase(p.getCategory()))
                    .toList();
        }

        if (pool.isEmpty()) throw new IllegalStateException("추천 가능한 후보가 없습니다.");

        Place picked = pool.get(new Random().nextInt(pool.size()));
        return picked.getPlaceId();
    }

    public PlanResponse adjustDraftItem(AdjustItemRequest req) {
        if (req.items == null || req.items.isEmpty())
            throw new IllegalArgumentException("items empty");
        if (req.index < 0 || req.index >= req.items.size())
            throw new IllegalArgumentException("index out of range");

        var list = new ArrayList<AdjustItemRequest.Item>(req.items);
        var target = list.get(req.index);

        String  finalPlaceId = target.place_id;
        String  finalTime    = target.time;
        String  finalNote    = target.note;
        Integer finalCost    = (target.est_cost == null ? 0 : target.est_cost);

        if (req.new_place_id != null && !req.new_place_id.isBlank()) {
            var oldPlace = supabaseService.fetchByPlaceId(target.place_id)
                    .orElseThrow(() -> new IllegalArgumentException("기존 장소를 찾을 수 없습니다: " + target.place_id));

            var newPlace = supabaseService.fetchByPlaceId(req.new_place_id)
                    .orElseThrow(() -> new IllegalArgumentException("새로운 장소를 찾을 수 없습니다: " + req.new_place_id));


            if (oldPlace.getCategory().equalsIgnoreCase(newPlace.getCategory())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "동일한 카테고리의 장소로는 변경할 수 없습니다.");
            }

            finalPlaceId = newPlace.getPlaceId();
        }

        if (req.new_time != null) finalTime = req.new_time;
        if (req.new_note != null) finalNote = req.new_note;
        if (req.new_est_cost != null) finalCost = req.new_est_cost;

        target.place_id = finalPlaceId;
        target.time     = finalTime;
        target.note     = finalNote;
        target.est_cost = finalCost;

        return toPlanResponseFromDraft(req.date, req.userLat, req.userLng, list);
    }

    public PlanResponse removeDraftItem(RemoveItemRequest req) {
        if (req.items == null || req.items.isEmpty())
            throw new IllegalArgumentException("items empty");
        if (req.index < 0 || req.index >= req.items.size())
            throw new IllegalArgumentException("index out of range");

        var list = new ArrayList<RemoveItemRequest.Item>(req.items);
        list.remove(req.index);

        var converted = new ArrayList<AdjustItemRequest.Item>();
        for (var it : list) {
            var x = new AdjustItemRequest.Item();
            x.time = it.time;
            x.place_id = it.place_id;
            x.note = it.note;
            x.est_cost = it.est_cost;
            converted.add(x);
        }
        return toPlanResponseFromDraft(req.date, req.userLat, req.userLng, converted);
    }

    /* ================================== 내부 헬퍼 ================================== */

    private double[] resolveCenter(PlanRequest req) {
        Double uLat = req.getUserLat();
        Double uLng = req.getUserLng();
        if (uLat != null && uLng != null && Double.isFinite(uLat) && Double.isFinite(uLng)
                && !(uLat == 0.0 && uLng == 0.0)) {
            return new double[]{ uLat, uLng };
        }
        String code = (req.getCode() == null ? "" : req.getCode().trim());
        if (!code.isEmpty()) {
            try {
                var opt = supabaseService.fetchByPlaceId(code);
                if (opt.isPresent()) {
                    Place p = opt.get();
                    if (p.getLat() != null && p.getLng() != null
                            && Double.isFinite(p.getLat()) && Double.isFinite(p.getLng())) {
                        return new double[]{ p.getLat(), p.getLng() };
                    }
                }
            } catch (Exception ignore) {}
        }
        return new double[]{ 37.5665, 126.9780 };
    }


    private List<Place> filterByRadiusInMemory(List<Place> src, double lat, double lng,
                                               double radiusKm, List<String> categories) {
        if (src == null || src.isEmpty()) return List.of();
        boolean hasCat = (categories != null && !categories.isEmpty());
        return src.stream()
                .filter(p -> p.getLat() != null && p.getLng() != null)
                .filter(p -> {
                    if (hasCat) {
                        String c = nvl(p.getCategory()).toLowerCase();
                        return categories.contains(c);
                    }
                    return true;
                })
                .filter(p -> HaversineUtil.distanceKm(lat, lng, p.getLat(), p.getLng()) <= radiusKm)
                .sorted(Comparator.comparingDouble(p ->
                        HaversineUtil.distanceKm(lat, lng, p.getLat(), p.getLng())))
                .collect(Collectors.toList());
    }

    private PlanResponse buildPlanResponseFromPlaces(List<Place> ordered,
                                                     double centerLat, double centerLng,
                                                     String startAt, String endAt,
                                                     String rationale) {
        Map<String, String> empty = Collections.emptyMap();
        List<String> ids = ordered.stream().map(Place::getPlaceId).toList();
        return buildPlanResponseWithTimes(ordered, ids, centerLat, centerLng, startAt, endAt, empty, rationale);
    }

    private PlanResponse buildPlanResponseWithTimes(List<Place> picked, List<String> orderIds,
                                                    double centerLat, double centerLng,
                                                    String startAt, String endAt,
                                                    Map<String, String> timeById, String rationale) {

        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < orderIds.size(); i++) order.put(orderIds.get(i), i);
        picked.sort(Comparator.comparingInt(p -> order.getOrDefault(p.getPlaceId(), Integer.MAX_VALUE)));

        LocalTime start = LocalTime.parse(startAt, HHMM);
        LocalTime end   = LocalTime.parse(endAt, HHMM);
        long totalMin   = Math.max(0, java.time.Duration.between(start, end).toMinutes());
        int n = Math.max(1, picked.size());
        long slot = Math.max(20, totalMin / n);

        List<PlanResponse.Item> items = new ArrayList<>();
        double totalKm = 0.0;
        int totalCost = 0;

        double prevLat = centerLat, prevLng = centerLng;
        LocalTime cur = start;

        for (Place p : picked) {
            double legKm = HaversineUtil.distanceKm(prevLat, prevLng, nz(p.getLat()), nz(p.getLng()));
            totalKm += legKm;

            String time = timeById.getOrDefault(p.getPlaceId(), cur.format(HHMM));
            int estCost = Math.max(0, p.getDiscountPercent());

            items.add(new PlanResponse.Item(
                    time, p.getPlaceId(), nvl(p.getName()), nvl(p.getCategory()), nvl(p.getAddress()),
                    nz(p.getLat()), nz(p.getLng()), round1(legKm), estCost, ""
            ));
            totalCost += estCost;
            cur = cur.plusMinutes(slot);
            prevLat = nz(p.getLat()); prevLng = nz(p.getLng());
        }
        return new PlanResponse(today(), round1(totalKm), totalCost, rationale, items);
    }
    private List<Place> applyCategoryLimits(List<Place> places) {
        Map<String, Integer> limits = Map.of(
                "카페", 2,
                "놀거리", 2,
                "음식점", 2
        );

        Map<String, Integer> counts = new HashMap<>();
        List<Place> result = new ArrayList<>();

        for (Place p : places) {
            String cat = p.getCategory();
            int used = counts.getOrDefault(cat, 0);

            if (!limits.containsKey(cat) || used < limits.get(cat)) {
                result.add(p);
                counts.put(cat, used + 1);
            }
        }
        return result;
    }
    private List<Place> avoidConsecutiveSameCategory(List<Place> places) {
        if (places.size() < 2) {
            return places;
        }

        List<Place> result = new ArrayList<>(places);

        for (int i = 1; i < result.size(); i++) {
            Place prev = result.get(i - 1);
            Place current = result.get(i);

            if (prev.getCategory().equalsIgnoreCase(current.getCategory())) {

                int swapIndex = -1;
                for (int j = i + 1; j < result.size(); j++) {
                    if (!result.get(j).getCategory().equalsIgnoreCase(prev.getCategory())) {
                        swapIndex = j;
                        break;
                    }
                }

                if (swapIndex != -1) {
                    Collections.swap(result, i, swapIndex);
                }
            }
        }
        return result;
    }


    private PlanResponse toPlanResponseFromDraft(String date, Double userLat, Double userLng,
                                                 List<AdjustItemRequest.Item> itemsIn) {
        var ids = itemsIn.stream().map(i -> i.place_id).toList();
        var placeById = supabaseService.fetchPlacesInOrder(ids).stream()
                .collect(Collectors.toMap(Place::getPlaceId, p -> p, (a,b)->a, LinkedHashMap::new));

        List<PlanResponse.Item> out = new ArrayList<>();
        double totalKm = 0.0;
        Double prevLat = userLat, prevLng = userLng;

        for (var it : itemsIn) {
            var p = placeById.get(it.place_id);
            if (p == null) continue;
            double legKm = 0.0;
            if (prevLat != null && prevLng != null) {
                legKm = HaversineUtil.distanceKm(prevLat, prevLng, nz(p.getLat()), nz(p.getLng()));
            }
            totalKm += legKm;
            prevLat = p.getLat();
            prevLng = p.getLng();

            out.add(new PlanResponse.Item(
                    it.time, p.getPlaceId(), p.getName(), p.getCategory(), p.getAddress(),
                    p.getLat(), p.getLng(), round1(legKm),
                    it.est_cost == null ? 0 : it.est_cost,
                    it.note == null ? "" : it.note
            ));
        }

        int totalEst = out.stream().mapToInt(PlanResponse.Item::getEstCost).sum();
        String d = (date == null || date.isBlank()) ? today() : date;
        return new PlanResponse(d, round1(totalKm), totalEst, "", out);
    }

    private List<Place> balanceCategories(List<Place> places) {
        Map<String, Integer> limits = Map.of(
                "카페", 2,
                "놀거리", 2,
                "음식점", 2
        );

        Map<String, Integer> counts = new HashMap<>();
        Map<String, Queue<Place>> byCat = new LinkedHashMap<>();
        for (Place p : places) {
            byCat.computeIfAbsent(p.getCategory(), k -> new LinkedList<>()).add(p);
        }

        List<Place> result = new ArrayList<>();
        boolean added;

        do {
            added = false;
            for (var entry : byCat.entrySet()) {
                String cat = entry.getKey();
                Queue<Place> q = entry.getValue();
                if (!q.isEmpty()) {
                    int used = counts.getOrDefault(cat, 0);
                    if (!limits.containsKey(cat) || used < limits.get(cat)) {
                        result.add(q.poll());
                        counts.put(cat, used + 1);
                        added = true;
                    } else {
                        // 이미 제한 초과면 그냥 버림
                        q.clear();
                    }
                }
            }
        } while (added);

        return result;
    }

    /* ===== GPT 응답 파싱 ===== */
    private record GItem(String time, String placeId) {}
    private List<GItem> parseGptItems(String planJson) {
        try {
            JsonNode root = om.readTree(planJson);
            JsonNode arr = root.path("items");
            List<GItem> list = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode it : arr) {
                    String id   = it.path("place_id").asText(null);
                    String time = it.path("time").asText(null);
                    if (id != null) list.add(new GItem(time, id));
                }
            }
            list.sort(Comparator.comparing(
                    g -> (g.time != null && g.time.matches("\\d{2}:\\d{2}")) ? g.time : "99:99"
            ));
            return list;
        } catch (Exception e) {
            log.warn("GPT 결과 파싱 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /* ---- helpers ---- */
    private String toJson(Object v){ try { return om.writeValueAsString(v); } catch(Exception e){ return (v instanceof Map)?"{}":"[]"; } }
    private String nvl(String s){ return s==null? "":s; }
    private String nz(String s, String def){ return (s==null||s.isBlank())? def:s; }
    private double nz(Double d){ return (d==null||!Double.isFinite(d))? 0.0:d; }
    private double nz(Double d, double def){ return (d==null||!Double.isFinite(d))? def:d; }
    private int nz(Integer v, int def){ return v==null? def:v; }
    private double round1(double v){ return Math.round(v*10.0)/10.0; }
    private String today(){ return java.time.LocalDate.now().toString(); }
}
