package com.example.domo.service;

import com.example.domo.model.Place;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

public class PlaceRowMapper implements RowMapper<Place> {

    @Override
    public Place mapRow(ResultSet rs, int rowNum) throws SQLException {
        Place p = new Place();

        p.setPlaceId(safeGetString(rs, "place_id"));
        p.setName(safeGetString(rs, "name"));
        p.setCategory(safeGetString(rs, "category"));
        p.setAddress(safeGetString(rs, "address"));

        p.setLat(safeGetDouble(rs, "lat"));
        p.setLng(safeGetDouble(rs, "lng"));

        p.setSido(safeGetString(rs, "sido"));
        p.setSigungu(safeGetString(rs, "sigungu"));

        p.setBenefit(safeGetString(rs, "benefit"));

        p.setDiscountPercent(safeGetInt(rs, "discountpercent"));

        int totalScore = (int) Math.round(safeGetDouble(rs, "totalscore"));
        p.setTotalScore(totalScore);

        p.setPopularity(0);

        return p;
    }

    private String safeGetString(ResultSet rs, String col) {
        try {
            String v = rs.getString(col);
            return (v == null) ? null : v;
        } catch (SQLException ignore) {
            return null;
        }
    }

    private int safeGetInt(ResultSet rs, String col) {
        try {
            Object obj = rs.getObject(col);
            if (obj == null) return 0;
            if (obj instanceof Number) return ((Number) obj).intValue();
            try {
                String s = String.valueOf(obj).trim();
                if (s.isEmpty()) return 0;
                return Integer.parseInt(s);
            } catch (Exception e) {
                return 0;
            }
        } catch (SQLException ignore) {
            return 0;
        }
    }

    private double safeGetDouble(ResultSet rs, String col) {
        try {
            Object obj = rs.getObject(col);
            if (obj == null) return 0.0;
            if (obj instanceof Number) return ((Number) obj).doubleValue();
            try {
                String s = String.valueOf(obj).trim();
                if (s.isEmpty()) return 0.0;
                return Double.parseDouble(s);
            } catch (Exception e) {
                return 0.0;
            }
        } catch (SQLException ignore) {
            return 0.0;
        }
    }
}
