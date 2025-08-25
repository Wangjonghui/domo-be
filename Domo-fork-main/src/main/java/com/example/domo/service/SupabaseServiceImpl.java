package com.example.domo.service;

import com.example.domo.model.Place;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SupabaseServiceImpl implements SupabaseService {

    private final JdbcTemplate jdbc;
    private final PlaceRowMapper rowMapper = new PlaceRowMapper();

    public SupabaseServiceImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Place> fetchPlaces(String sido, String sigungu, int limit, int offset) {
        String sql =
                "SELECT place_id, name, category, address, lat, lng, " +
                        "       COALESCE(discountpercent,0) AS discountpercent, " +
                        "       COALESCE(totalscore,0)      AS totalscore, " +
                        "       sido, sigungu, COALESCE(benefit,'') AS benefit " +
                        "FROM places " +
                        "WHERE (COALESCE(?, '') = '' OR sido = ?) " +
                        "  AND (COALESCE(?, '') = '' OR sigungu = ?) " +
                        "ORDER BY totalscore DESC NULLS LAST, discountpercent DESC NULLS LAST " +
                        "LIMIT ? OFFSET ?";

        return jdbc.query(sql, rowMapper,
                sido, sido,
                sigungu, sigungu,
                limit, offset
        );
    }

    @Override
    public List<Place> fetchPlacesInOrder(List<String> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) return Collections.emptyList();
        String inSql = placeIds.stream().map(id -> "?::uuid").collect(Collectors.joining(","));
        String sql =
                "SELECT place_id, name, category, address, lat, lng, " +
                        "       COALESCE(discountpercent,0) AS discountpercent, " +
                        "       COALESCE(totalscore,0)      AS totalscore, " +
                        "       sido, sigungu, COALESCE(benefit,'') AS benefit " +
                        "FROM places WHERE place_id IN (" + inSql + ")";
        List<Place> fetched = jdbc.query(sql, rowMapper, placeIds.toArray());
        Map<String,Integer> order = new HashMap<>();
        for (int i=0;i<placeIds.size();i++) order.put(placeIds.get(i), i);
        fetched.sort(Comparator.comparingInt(p -> order.getOrDefault(p.getPlaceId(), Integer.MAX_VALUE)));
        return fetched;
    }

    @Override
    public Optional<Place> fetchByPlaceId(String placeId) {
        String sql =
                "SELECT place_id, name, category, address, lat, lng, " +
                        "       COALESCE(discountpercent,0) AS discountpercent, " +
                        "       COALESCE(totalscore,0)      AS totalscore, " +
                        "       sido, sigungu, COALESCE(benefit,'') AS benefit " +
                        "FROM places WHERE place_id = ?::uuid";
        var list = jdbc.query(sql, rowMapper, placeId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public int countPlaces(String sido, String sigungu) {
        String sql =
                "SELECT COUNT(*) FROM places " +
                        "WHERE (COALESCE(?, '') = '' OR sido = ?) " +
                        "  AND (COALESCE(?, '') = '' OR sigungu = ?)";
        Integer cnt = jdbc.queryForObject(sql, Integer.class, sido, sido, sigungu, sigungu);
        return (cnt == null ? 0 : cnt);
    }

    @Override
    public Optional<Place> fetchNearestPlace(Double lat, Double lng, double radiusKm) {
        String sql =
                "SELECT place_id, name, category, address, lat, lng, " +
                        "       COALESCE(discountpercent,0) AS discountpercent, " +
                        "       COALESCE(totalscore,0)      AS totalscore, " +
                        "       sido, sigungu, COALESCE(benefit,'') AS benefit " +
                        "FROM places " +
                        "WHERE earth_distance(ll_to_earth(?, ?), ll_to_earth(lat, lng)) <= ? * 1000 " +
                        "ORDER BY earth_distance(ll_to_earth(?, ?), ll_to_earth(lat, lng)) ASC " +
                        "LIMIT 1";

        List<Place> list = jdbc.query(sql, rowMapper,
                lat, lng,
                radiusKm,
                lat, lng
        );

        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** ✅ 기준 좌표/반경으로 DB에서만 후보 조회 (카테고리 필터 옵션) */
    @Override
    public List<Place> fetchPlacesNear(double centerLat, double centerLng, double radiusKm, List<String> categories, int limit) {
        // earthdistance 확장을 사용하는 원쿼리 (PostgreSQL)
        String base =
                "SELECT place_id, name, category, address, lat, lng, " +
                        "       COALESCE(discountpercent,0) AS discountpercent, " +
                        "       COALESCE(totalscore,0)      AS totalscore, " +
                        "       sido, sigungu, COALESCE(benefit,'') AS benefit " +
                        "FROM places " +
                        "WHERE earth_distance(ll_to_earth(?, ?), ll_to_earth(lat, lng)) <= ? * 1000 ";

        List<Object> args = new ArrayList<>();
        args.add(centerLat);
        args.add(centerLng);
        args.add(radiusKm);

        StringBuilder sql = new StringBuilder(base);
        if (categories != null && !categories.isEmpty()) {
            // lower(category) IN (?, ?, ...)
            String in = categories.stream().map(s -> "?").collect(Collectors.joining(","));
            sql.append("AND lower(category) IN (").append(in).append(") ");
            for (String c : categories) args.add(c == null ? null : c.toLowerCase());
        }

        sql.append("ORDER BY totalscore DESC NULLS LAST, discountpercent DESC NULLS LAST, name ASC ");
        sql.append("LIMIT ?");

        args.add(Math.max(1, limit));

        return jdbc.query(sql.toString(), rowMapper, args.toArray());
    }
}
