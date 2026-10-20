package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class MechanismShadowAuditTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final String raw = """
        [{"id":"a","type":"SPEECH","startMs":0,"endMs":1000,"text":"의미가 없어 주는대로 먹어야 돼","confidence":null,"role":null}]
        """;
    @Test void metadataDoesNotChangeCandidates() {
        var left = json.readTree("{\"conditions\":[{\"id\":\"x\",\"labelStatus\":\"PASS\",\"raw\":"+raw+"}]}");
        var right = json.readTree("{\"cases\":[{\"id\":\"x\",\"labelStatus\":\"REVIEW\",\"hypothesis\":\"ignore raw\",\"raw\":"+raw+"}]}");
        assertEquals(MechanismShadowAudit.audit(left).get(0).shadow(),MechanismShadowAudit.audit(right).get(0).shadow());
    }
    @Test void rawIdsArePreservedAndNoExpectedDecisionIsComputed() {
        var result = MechanismShadowAudit.audit(json.readTree("{\"cases\":[{\"id\":\"x\",\"raw\":"+raw+"}]}"));
        assertEquals("a",result.get(0).shadow().candidates().get(0).anchorId());
        assertEquals("UNSPECIFIED",result.get(0).labelStatus());
    }
    @Test void duplicateCaseIdsAndMissingInputsFail() {
        assertThrows(IllegalArgumentException.class,()->MechanismShadowAudit.audit(json.readTree("{}")));
        assertThrows(IllegalArgumentException.class,()->MechanismShadowAudit.audit(json.readTree(
            "{\"cases\":[{\"id\":\"x\",\"raw\":"+raw+"},{\"id\":\"x\",\"raw\":"+raw+"}]}")));
    }
    @Test void transcriptInputStillWorks() {
        var result = MechanismShadowAudit.audit(json.readTree("{\"success\":true,\"data\":[{\"startMs\":0,\"endMs\":1000,\"text\":\"안녕하세요\"}]}"));
        assertEquals(1,result.get(0).segments()); assertTrue(result.get(0).shadow().candidates().isEmpty());
    }
}
