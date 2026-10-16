package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class LongVideoRoutingTrialTest {
    @TempDir Path temp;
    @Test void extractionPreservesDatabaseIdsTimesNoiseAndOnlyScopedVideos() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:long-trial-"+UUID.randomUUID())) {
            try(var s=c.createStatement()) {
                s.execute("CREATE TABLE video(id BIGINT,filename VARCHAR,duration_sec INT)");
                s.execute("CREATE TABLE transcript_segment(id BIGINT,video_id BIGINT,start_ms BIGINT,end_ms BIGINT,text VARCHAR)");
                s.execute("INSERT INTO video VALUES(67,'v4.mp4',627),(68,'v5.mp4',911),(69,'v6.mp4',900)");
                s.execute("INSERT INTO transcript_segment VALUES(102,67,20,30,'와.'),(101,67,0,20,'  교정하지 않음  '),(103,68,0,9,'미전송'),(104,69,5,10,'그대로')");
            }
            var sources=LongVideoRoutingTrial.sources(c);
            assertThat(sources).extracting(LongVideoRoutingTrial.Source::caseId).containsExactly("v4","v6");
            assertThat(sources.get(0).raw()).extracting(ReviewInput.Segment::id).containsExactly("stt-101","stt-102");
            assertThat(sources.get(0).raw().get(0).text()).isEqualTo("  교정하지 않음  ");
            assertThat(sources.get(0).raw().get(1).text()).isEqualTo("와.");
            assertThat(sources.get(1).raw().get(0).startMs()).isEqualTo(5);
            assertThat(JSON.writeValueAsString(sources)).doesNotContain("미전송").contains("NOT_NORMAL_GOLD");
        }
    }
    @Test void newBudgetCannotReuseOldScopeAndReservesBeforeDispatch() throws Exception {
        var budget=new LongVideoRoutingTrial.Budget(temp); budget.reserve(1000);
        assertThat(budget.attempts).isEqualTo(1); assertThat(budget.reserved).isPositive();
        assertThat(Files.readString(temp.resolve("reservation.json"))).contains("\"approvedEstimatedUsd\":3");
        budget.reserved=2.999;
        assertThatThrownBy(() -> budget.reserve(1000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(budget.attempts).isEqualTo(1); assertThat(budget.exhausted).isTrue();
        var count=new LongVideoRoutingTrial.Budget(temp); count.attempts=400;
        assertThatThrownBy(() -> count.reserve(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count.reserved).isZero();
        byte[] bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> LongVideoRoutingTrial.authorized(bytes,ReferenceOnlyTrial.sha(bytes),V7RoutingVideoTrial.CONSENT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("consent");
    }
    LongVideoRoutingTrial.Plan plan() throws Exception {
        var p=new V7RoutingVideoTrialTest().plan();
        var sources=new ArrayList<LongVideoRoutingTrial.Source>();
        for(int i=0;i<2;i++) {
            String id=i==0?"v4":"v6"; var raw=p.conditions().get(i).raw();
            sources.add(new LongVideoRoutingTrial.Source(id,i==0?67:69,id+".mp4",900,"PRIOR_WARNINGS_NOT_ADJUDICATED_NOT_NORMAL_GOLD",raw,
                    ReviewGuidelineLibrary.retrievalHash(JSON.writeValueAsString(raw))));
        }
        return LongVideoRoutingTrial.prepare(sources,p.runtimeText().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                p.registryText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void planFixesRolesOffRouterTwelveAndRefusesChangedTranscript() throws Exception {
        var p=plan(); assertThat(p.maxRouterCalls()).isEqualTo(12); assertThat(p.sourceRelationsEnabled()).isFalse();
        var copy=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p); copy.put("maxRouterCalls",99);
        var changed=JSON.treeToValue(copy,LongVideoRoutingTrial.Plan.class);
        assertThatThrownBy(() -> LongVideoRoutingTrial.validate(changed)).isInstanceOf(IllegalArgumentException.class);
        copy=(tools.jackson.databind.node.ObjectNode)JSON.valueToTree(p);
        ((tools.jackson.databind.node.ObjectNode)copy.path("sources").get(0).path("raw").get(0)).put("text","changed");
        var changedRaw=JSON.treeToValue(copy,LongVideoRoutingTrial.Plan.class);
        assertThatThrownBy(() -> LongVideoRoutingTrial.validate(changedRaw)).isInstanceOf(IllegalArgumentException.class);
    }
}
