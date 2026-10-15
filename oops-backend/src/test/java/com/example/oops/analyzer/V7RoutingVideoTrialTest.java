package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class V7RoutingVideoTrialTest {
    V7RoutingVideoTrial.Plan plan() throws Exception {
        var v1=JSON.writeValueAsBytes(Map.of("transcript",List.of(Map.of("text","교정하지 않는 합성 대사","startMs",10,"endMs",100))));
        var v2=JSON.writeValueAsBytes(new MechanismRoutingTrialTest().plan());
        var archive=new ContextRelationRetrievalTest().archive();
        var registry=JSON.writeValueAsBytes(Map.of("schemaVersion","context-routing-keys-1","keys",List.of(new ContextMechanismRouter.Key("graphic-body-metaphor","신체 비유 검색"))));
        return V7RoutingVideoTrial.prepare(v1,v2,JSON.writeValueAsBytes(archive),registry);
    }
    @Test void planUsesOnlyTwoFrozenTranscriptsAndCurrentThreeReferenceDefault() throws Exception {
        var p=plan(); assertThat(p.conditions()).extracting(MechanismRoutingTrial.Condition::id).containsExactly("v1","v2");
        assertThat(p.sourceRelationsEnabled()).isFalse(); assertThat(p.repeats()).isEqualTo(2);
        var line=p.conditions().get(0).raw().get(0); assertThat(line.text()).isEqualTo("교정하지 않는 합성 대사");
        assertThat(line.startMs()).isEqualTo(10); assertThat(line.id()).isEqualTo("stt-index-0");
        assertThat(V7RoutingVideoTrial.library(p).relationArchive()).isTrue();
        assertThat(JSON.writeValueAsString(p)).doesNotContain("forbiddenFixedCandidate","proposedEvidence");
    }
    @Test void scopeHashesAndFreshConsentAreValidatedBeforeCredentials() throws Exception {
        var p=plan(); var node=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p);
        byte[] bytes=JSON.writeValueAsBytes(p);
        assertThatThrownBy(() -> V7RoutingVideoTrial.authorized(bytes,ReferenceOnlyTrial.sha(bytes),"old-consent"))
                .isInstanceOf(IllegalArgumentException.class);
        node.put("sourceRelationsEnabled",true);
        var rolesOn=JSON.treeToValue(node,V7RoutingVideoTrial.Plan.class);
        assertThatThrownBy(() -> V7RoutingVideoTrial.validate(rolesOn))
                .isInstanceOf(IllegalArgumentException.class);
        node=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p); node.put("runtimeText",p.runtimeText()+" ");
        var changed=JSON.treeToValue(node,V7RoutingVideoTrial.Plan.class);
        assertThatThrownBy(() -> V7RoutingVideoTrial.validate(changed)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void transportRejectsMediaOtherModelsToolsAndEndpointsBeforeBudget() {
        var body=new LinkedHashMap<String,Object>(); body.put("model","gpt-6-luna"); body.put("reasoning_effort","none");
        body.put("messages",List.of(Map.of("role","user","content","고정 텍스트")));
        String endpoint="https://api.openai.com/v1/chat/completions";
        assertThat(JSON.readTree(V7RoutingVideoTrial.guardBody(JSON.writeValueAsBytes(body),endpoint)).path("max_completion_tokens").asInt()).isEqualTo(8000);
        assertThatThrownBy(() -> V7RoutingVideoTrial.guardBody(JSON.writeValueAsBytes(body),"https://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        for(var change:List.of(Map.of("model","other"),Map.of("tools",List.of()),
                Map.of("messages",List.of(Map.of("role","user","content",List.of(Map.of("type","image_url","image_url","not-sent"))))))) {
            var altered=new LinkedHashMap<>(body); altered.putAll(change);
            assertThatThrownBy(() -> V7RoutingVideoTrial.guardBody(JSON.writeValueAsBytes(altered),endpoint)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
