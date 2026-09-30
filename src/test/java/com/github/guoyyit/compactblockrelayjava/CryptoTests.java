package com.github.guoyyit.compactblockrelayjava;

import com.github.guoyyit.compactblockrelayjava.crypto.Hashes;
import com.github.guoyyit.compactblockrelayjava.crypto.SipHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoTests {
    @Test
    void sha256EmptyMatchesKnownVector() {
        assertThat(Hashes.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sipHashDeterministicAndKeySensitive() {
        String txid = Hashes.txidOf("hello");
        long a = SipHash.shortId(1L, 2L, txid);
        long b = SipHash.shortId(1L, 2L, txid);
        long c = SipHash.shortId(1L, 3L, txid);
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void sipHashHandlesNonEightMultipleLength() {
        String txid = "abc";
        long value = SipHash.shortId(0L, 0L, txid);
        assertThat(value).isEqualTo(SipHash.shortId(0L, 0L,
                new String("abc".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)));
    }
}
