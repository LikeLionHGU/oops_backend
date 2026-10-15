package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OpenAiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class ContextRoutingProbeExecutionTest {
    @TempDir Path temp;
    private final OpenAiClient client=new OpenAiClient(null,new OpenAiProperties("not-a-real-key",null,null,
            "https://api.openai.com/v1","gpt-6-luna","none",Duration.ofSeconds(45),null));
    private final ContextRoutingProbePlan.Task task=new ContextRoutingProbePlan.Task("q-r1","q",1,"고정 프롬프트","{\"raw\":[]}","unused");
    private byte[] body() {
        var map=new LinkedHashMap<>(client.replayRequestBody(task.system(),task.input())); map.put("max_completion_tokens",1024);
        return JSON.writeValueAsBytes(map);
    }
    private ContextRoutingProbeExecution.Guard guard(double cap) {
        return new ContextRoutingProbeExecution.Guard(new TextBoundaryTrial.Budget(cap,.10,.50,temp));
    }
    @Test void reservesBeforeOneDispatchAndRejectsImplicitRetry() throws Exception {
        var guard=guard(1); guard.arm(task,client); guard.before(body(),"https://api.openai.com/v1/chat/completions");
        assertThat(guard.budget.attempts).isEqualTo(1); assertThat(guard.budget.reserved).isGreaterThan(.004);
        assertThat(Files.readString(temp.resolve("reservation.json"))).contains("8000");
        assertThatThrownBy(() -> guard.before(body(),"https://api.openai.com/v1/chat/completions"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(guard.budget.attempts).isEqualTo(1);
    }
    @Test void rejectsChangedPromptModelOutputAndExtraFieldsBeforeReservation() {
        for(var field:List.of("model","max_completion_tokens","messages","tools")) {
            var guard=guard(1); guard.arm(task,client);
            var changed=(tools.jackson.databind.node.ObjectNode)JSON.readTree(body()); changed.put(field,"changed");
            assertThatThrownBy(() -> guard.before(JSON.writeValueAsBytes(changed),"https://api.openai.com/v1/chat/completions"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(guard.budget.attempts).isZero();
        }
    }
    @Test void rejectsOtherEndpointsAndFortyNinthAttemptAndOverBudgetBeforeDispatch() {
        var guard=guard(1); guard.arm(task,client);
        assertThatThrownBy(() -> guard.before(body(),"https://example.com/v1/chat/completions"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(guard.budget.attempts).isZero(); guard.budget.attempts=48;
        assertThatThrownBy(() -> guard.before(body(),"https://api.openai.com/v1/chat/completions"))
                .isInstanceOf(IllegalArgumentException.class);
        var tiny=guard(.0001); tiny.arm(task,client);
        assertThatThrownBy(() -> tiny.before(body(),"https://api.openai.com/v1/chat/completions"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tiny.budget.exhausted).isTrue(); assertThat(tiny.budget.attempts).isZero();
    }
    @Test void approvalMustMatchExactPlanAndFreshConsentBeforeReadingCredentials() throws Exception {
        byte[] bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ContextRoutingProbeExecution.authorized(bytes,"a".repeat(64),ContextRoutingProbeExecution.CONSENT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("consent");
        assertThatThrownBy(() -> ContextRoutingProbeExecution.authorized(bytes,ReferenceOnlyTrial.sha(bytes),"old-consent"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("consent");
    }
    @Test void coveringEveryRoutingKeyDoesNotGuaranteeTheExactSameKeyFlowIsRetained() {
        var base=new ContextExampleSelectionTest().archive(3);
        var examples=new ArrayList<ReviewGuidelineLibrary.ContextExample>();
        for(int i=0;i<3;i++) {
            var e=base.examples().get(i);
            examples.add(new ReviewGuidelineLibrary.ContextExample(e.id(),e.familyId(),e.mechanismIds(),e.channels(),e.context(),
                    null,List.of(i<2?"local-life-devaluation":"service-choice-devaluation")));
        }
        var matches=List.of(new ContextExampleSelector.Match(examples.get(0),.7,true),
                new ContextExampleSelector.Match(examples.get(1),0,true),new ContextExampleSelector.Match(examples.get(2),0,true));
        var plan=ContextReferenceBalance.select(matches,Set.of("local-life-devaluation","service-choice-devaluation"),2,false,chosen -> true);
        assertThat(plan.selected().stream().map(ReviewGuidelineLibrary.ContextExample::id)).containsExactly("source-0","source-2");
        assertThat(plan.trace().uncoveredKeys()).isEmpty();
        assertThat(plan.trace().omissions()).contains(new ContextReferenceBalance.Omission("source-1","COUNT_LIMIT"));
        // Reproduces a known boundary, not a fix or proof that the omitted flow is unnecessary.
    }
}
