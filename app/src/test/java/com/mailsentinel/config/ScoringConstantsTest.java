package com.mailsentinel.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoringConstantsTest {

    private static List<String> hits(int count) {
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            hits.add("hit" + i);
        }
        return hits;
    }

    @Test
    void joinsEverythingWhenTheListFitsUnderTheCap() {
        assertEquals("hit0; hit1; hit2", ScoringConstants.joinHits(hits(3)));
        assertEquals("", ScoringConstants.joinHits(List.of()));
    }

    @Test
    void joinsEverythingAtExactlyTheCapWithNoSuffix() {
        String joined = ScoringConstants.joinHits(hits(ScoringConstants.MAX_HITS_PER_DETAIL));

        assertEquals(ScoringConstants.MAX_HITS_PER_DETAIL, joined.split("; ").length);
        assertEquals(-1, joined.indexOf("more"), "nothing was dropped, so nothing should be claimed dropped");
    }

    @Test
    void namesTheFirstHitsAndCountsTheRestOnceOverTheCap() {
        String joined = ScoringConstants.joinHits(hits(ScoringConstants.MAX_HITS_PER_DETAIL + 15));

        assertEquals("hit0", joined.split("; ")[0]);
        assertEquals(ScoringConstants.MAX_HITS_PER_DETAIL + 1, joined.split("; ").length,
                "the capped hits plus the one summary segment");
        assertEquals("...and 15 more", joined.substring(joined.lastIndexOf("; ") + 2));
    }
}
