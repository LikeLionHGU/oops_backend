package com.example.oops.controller;

import com.example.oops.common.ApiResponse;
import com.example.oops.service.ScriptImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Loopback/local only via LocalDiagnosticsAccess; JSON POST starts analysis. */
@RestController
@Profile("local")
@RequiredArgsConstructor
public class ScriptImportController {
    private final ScriptImportService imports;
    @PostMapping(value="/api/v1/videos/analysis/import-script", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ScriptImportService.Response>> create(@RequestBody ScriptImportService.Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(imports.create(request)));
    }
}
