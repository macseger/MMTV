package com.example.mmtv.repository

import org.junit.Assert.*
import org.junit.Test

class EpgChannelNamesTest {
    @Test fun matchesSwedishPrefixVariantsWithoutMatchingOtherCountries() {
        for (name in listOf("SE: SVT 1 HD", "SE SVT 1", "SWE: SVT 1 FHD", "SWE-SVT 1", "Sweden | SVT 1")) {
            assertEquals("SVT1", normalizeEpgChannelName(name))
        }
        assertNotEquals(normalizeEpgChannelName("SE: TV4"), normalizeEpgChannelName("NO: TV4"))
    }

    @Test fun decodesEncodedTitlesAndKeepsPlainText() {
        assertEquals("Nyheter", decodeEpgText("TnloZXRlcg=="))
        assertEquals("Nyheter kl 18", decodeEpgText("Nyheter kl 18"))
        assertEquals("News", decodeEpgText("News"))
        assertNull(decodeEpgText(null))
    }
}
