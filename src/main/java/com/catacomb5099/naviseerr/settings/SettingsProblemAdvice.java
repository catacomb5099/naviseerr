package com.catacomb5099.naviseerr.settings;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Every settings controller answers a {@link SettingsProblem} the same way: 400 {@code {"message": "<sentence>"}}. */
@RestControllerAdvice(basePackageClasses = SettingsProblem.class)
public class SettingsProblemAdvice {

    @ExceptionHandler(SettingsProblem.class)
    ResponseEntity<Map<String, String>> problem(SettingsProblem e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
