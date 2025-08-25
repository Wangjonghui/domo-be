package com.example.domo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class GptService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http;
    private final ObjectMapper om = new ObjectMapper();

    private final String apiKey;

    public GptService(OkHttpClient openAiHttpClient,
                      @Value("${openai.api.key:${OPENAI_API_KEY:}}") String apiKey) {
        this.http = openAiHttpClient;
        this.apiKey = apiKey;
    }

    public String completeJson(String prompt) {
        try {
            String body = om.createObjectNode()
                    .put("model", "gpt-3.5-turbo")
                    .put("temperature", 0.2)
                    .put("max_tokens", 800)
                    .set("messages", om.createArrayNode()
                            .add(om.createObjectNode()
                                    .put("role", "user")
                                    .put("content", prompt)))
                    .toString();

            Request request = new Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body, JSON))
                    .build();

            try (Response resp = http.newCall(request).execute()) {
                if (!resp.isSuccessful()) {
                    String err = (resp.body() != null) ? resp.body().string() : "";
                    throw new IOException("OpenAI error " + resp.code() + ": " + err);
                }
                String resJson = resp.body().string();
                JsonNode root = om.readTree(resJson);
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                return content.isMissingNode() ? "" : content.asText();
            }
        } catch (Exception e) {
            throw new RuntimeException("OpenAI 요청 실패: " + e.getMessage(), e);
        }
    }

    // GptService.java
    public String planOneDayJson(String regionLabel, String placesJson, String userPrefJson) {
        String template = """
    당신은 여행 코스 플래너입니다. 입력된 DB 후보들을 보고 이동거리, 할인혜택(원/%%), 점수(totalscore)를 균형 있게 고려해,
    **반드시 유효한 JSON만** 출력하세요. 설명 금지.

    제약:
    - 모든 장소 식별자는 문자열 UUID인 "place_id"를 사용합니다.
    - 반드시 입력 후보의 place_id만 사용하세요(새 id 금지).
    - 시간은 HH:MM(24h) 형식.
    - 일정은 최소 4개 최대 6개로 설정한다.
    - 같은 "gategory" 를 연속으로 배치하지 마세요.
    - 하루 일정에서 카페는 합계 최대 2곳으로 제한하고, 음식점은 최대 3곳으로 제한하고, 그 외 활동(공원/전시/체험/쇼핑 등)을 최소 1개 이상 포함하세요.
                - 출력 직전에 다음 체크리스트를 점검하고, 하나라도 어기면 스스로 수정한 뒤 출력하세요:
                  (1) 인접 아이템의 카테고리가 모두 다름 (2) 카페 ≤ 2, 음식점 ≤ 3 (3) 놀거리 ≥ 1

    입력:
    지역: %s
    후보목록(places): %s
    사용자선호(userPref): %s

    출력 JSON 스키마:
    {
      "date": "YYYY-MM-DD",
      "items": [
        { "time": "HH:MM", "place_id": "uuid-string", "note": "이유(최대60자)", "est_cost": 0 }
      ],
      "summary": { "total_est_cost": 0, "rationale": "선정근거(최대200자)" }
    }
    """;
        String prompt = String.format(template, regionLabel, placesJson, userPrefJson);
        return completeJson(prompt);
    }
}
