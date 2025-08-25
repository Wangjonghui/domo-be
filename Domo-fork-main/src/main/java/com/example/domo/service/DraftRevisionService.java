package com.example.domo.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DraftRevisionService {
    private final ConcurrentHashMap<String, Integer> revs = new ConcurrentHashMap<>();

    public int get(String draftId) {
        if (draftId == null || draftId.isBlank()) return 0;
        return revs.getOrDefault(draftId, 0);
    }

    public void assertAndBump(String draftId, Integer requestRevision) {
        if (draftId == null || draftId.isBlank()) return;
        int current = revs.getOrDefault(draftId, 0);
        if (requestRevision == null || !requestRevision.equals(current)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Revision mismatch. Please refresh."
            );
        }
        revs.put(draftId, current + 1);
    }
}
