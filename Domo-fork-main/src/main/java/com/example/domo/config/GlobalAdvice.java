package com.example.domo.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalAdvice {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onAny(Exception e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", e.getClass().getSimpleName(),
                "message", e.getMessage()
        ));
    }
}
