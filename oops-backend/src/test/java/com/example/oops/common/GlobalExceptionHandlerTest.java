package com.example.oops.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    @RestController
    static class JsonOnlyController {
        @PostMapping(path = "/api/v1/samples", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> create(@RequestBody Map<String, String> body) { return body; }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new JsonOnlyController())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void unsupportedMethodReturns405WithAllowHeaderAndErrorCode() throws Exception {
        mvc.perform(get("/api/v1/samples"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test void unsupportedContentTypeReturns415WithErrorCode() throws Exception {
        mvc.perform(post("/api/v1/samples").contentType(MediaType.TEXT_PLAIN).content("plain"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
