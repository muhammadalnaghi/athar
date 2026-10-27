package com.athar.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MerchantSpecificityTest {

    @Test
    fun `specific merchant key prefers normalized merchant`() {
        assertThat(
            specificMerchantKey(
                merchantNormalized = "jarir bookstore",
                merchant = "Ignored Display Name",
            ),
        ).isEqualTo("jarir bookstore")
    }

    @Test
    fun `specific merchant key falls back to merchant when normalized is blank`() {
        assertThat(
            specificMerchantKey(
                merchantNormalized = "",
                merchant = "Brew Lab",
            ),
        ).isEqualTo("brew lab")
    }

    @Test
    fun `generic merchant keys are not specific`() {
        val genericKeys = listOf(
            "bank",
            "cash",
            "merchant",
            "online purchase",
            "payment",
            "purchase",
            "bank transfer",
            "كاش",
            "شراء",
            "دفع",
        )

        genericKeys.forEach { key ->
            assertThat(key.isSpecificMerchantKey()).isFalse()
        }
    }

    @Test
    fun `held processor descriptors are never a specific merchant identity`() {
        for (descriptor in listOf("public*pend", "public* pend", "PUBLIC*PENDING")) {
            assertThat(descriptor.isSpecificMerchantKey()).isFalse()
            assertThat(specificMerchantKey(descriptor, descriptor)).isNull()
        }
    }

    @Test
    fun `display hold evidence cannot disappear behind a normalized merchant label`() {
        assertThat(specificMerchantKey("public ride", "PUBLIC* PEND")).isNull()
        assertThat(specificMerchantKey("PUBLIC*PENDING", "Public Ride")).isNull()
    }

    @Test
    fun `ordinary PEND merchant spellings are not broad substring rejected`() {
        for (merchant in listOf("pend*books", "public*pendulum", "suspend*shop")) {
            assertThat(specificMerchantKey(merchant, merchant)).isEqualTo(merchant)
        }
    }

    @Test
    fun `digit only keys are not specific`() {
        assertThat("1234".isSpecificMerchantKey()).isFalse()
        assertThat("+966-123".isSpecificMerchantKey()).isFalse()
    }
}
