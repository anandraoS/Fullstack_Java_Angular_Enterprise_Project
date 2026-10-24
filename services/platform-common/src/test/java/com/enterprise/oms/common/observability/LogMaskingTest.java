package com.enterprise.oms.common.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogMaskingTest {

    @Test
    void masksEmails() {
        assertThat(LogMasking.maskEmail("alice@example.com")).isEqualTo("a***@example.com");
        assertThat(LogMasking.maskEmail("Order for bob.smith@mail.example.org confirmed"))
                .isEqualTo("Order for b***@mail.example.org confirmed");
        assertThat(LogMasking.maskEmail(null)).isNull();
        assertThat(LogMasking.maskEmail("no email here")).isEqualTo("no email here");
    }

    @Test
    void masksTokensAndSecretsInFreeText() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl";
        assertThat(LogMasking.sanitize("auth failed for " + jwt)).doesNotContain(jwt).contains("***jwt***");
        assertThat(LogMasking.sanitize("Authorization: Bearer abc.def-123")).doesNotContain("abc.def-123");
        assertThat(LogMasking.sanitize("login password=hunter2 ok")).doesNotContain("hunter2");
        assertThat(LogMasking.sanitize("mail to carol@example.test failed")).contains("c***@example.test");
        assertThat(LogMasking.maskToken("abcdefgh")).isEqualTo("abcd***");
        assertThat(LogMasking.maskToken("ab")).isEqualTo("a***");
    }

    @Test
    void stripsNewlines() {
        assertThat(LogMasking.stripNewlines("a\r\nb")).isEqualTo("a__b");
    }
}
