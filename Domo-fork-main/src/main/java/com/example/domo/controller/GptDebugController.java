package com.example.domo.controller;

import com.example.domo.service.GptService;
import com.example.domo.service.SupabaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Profile("dec")
@RestController
@RequestMapping("/api/_debug")
public class GptDebugController {

    private final GptService gpt;
    private final SupabaseService supabase;
    private final ObjectMapper om = new ObjectMapper();

    public GptDebugController(GptService gpt, SupabaseService supabase) {
        this.gpt = gpt;
        this.supabase = supabase;
    }

    @GetMapping(value="/gpt-with-db", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> withDb(
            @RequestParam(required=false) String sido,
            @RequestParam(required=false) String sigungu
    ) throws Exception {
        var top = supabase.fetchPlaces(sido, sigungu, 10, 0);
        var arr = new ArrayList<Map<String,Object>>();
        top.forEach(p -> {
            var m = new LinkedHashMap<String,Object>();
            m.put("place_id", p.getPlaceId()); // ★ UUID
            m.put("name", p.getName());
            m.put("category", p.getCategory());
            m.put("address", p.getAddress());
            m.put("lat", p.getLat());
            m.put("lng", p.getLng());
            m.put("discountPercent", p.getDiscountPercent());
            m.put("totalscore", p.getTotalScore());
            arr.add(m);
        });
        String placesJson = om.writeValueAsString(arr);

        String prefJson = "{\"startTime\":\"09:00\",\"endTime\":\"21:00\",\"pace\":\"NORMAL\"}";

        String raw = gpt.planOneDayJson("AUTO", placesJson, prefJson);

        Object json = om.readValue(raw, Object.class);
        return ResponseEntity.ok(json);
    }
}
