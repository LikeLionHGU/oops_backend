package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class ReportedContextModelComparisonTest {
    byte[] request(String reference){
        return JSON.writeValueAsBytes(Map.of("messages",List.of(Map.of("content","prefix contextReference={\"referenceContexts\":"+reference+"}\nrepair suffix"))));
    }
    @Test void permitsOnlyExactApprovedReferenceOrNoReference(){
        String allowed="{\"approved\":\"synthetic\"}";
        assertThatCode(()->ReportedContextModelComparison.restrictRequest(request("["+allowed+"]"),allowed)).doesNotThrowAnyException();
        assertThatCode(()->ReportedContextModelComparison.restrictRequest(request("[]"),allowed)).doesNotThrowAnyException();
        assertThatThrownBy(()->ReportedContextModelComparison.restrictRequest(request("[{\"different\":true}]"),allowed)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->ReportedContextModelComparison.restrictRequest(request("["+allowed+","+allowed+"]"),allowed)).isInstanceOf(RuntimeException.class);
    }
}
