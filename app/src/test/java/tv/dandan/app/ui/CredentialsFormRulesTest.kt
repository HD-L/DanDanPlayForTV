package tv.dandan.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.dandan.core.ApiCredentials

class CredentialsFormRulesTest {
    @Test fun clearingStoredSecretDisablesTheWholeCredentialPair() {
        val value = CredentialsFormRules.normalize("stored-app", "")
        assertEquals(ApiCredentials(), value)
        assertFalse(value.configured)
    }

    @Test fun whitespaceOnlySecretAlsoClearsTheOldAppId() {
        assertEquals(ApiCredentials(), CredentialsFormRules.normalize("stored-app", "   "))
    }

    @Test fun submittedCompletePairIsTrimmedAndRetained() {
        assertEquals(ApiCredentials("new-app", "new-secret"), CredentialsFormRules.normalize(" new-app ", " new-secret "))
    }

    @Test fun secretWithoutAppIdIsRejectedBeforeThePhoneSessionIsConsumed() {
        assertThrows(IllegalArgumentException::class.java) { CredentialsFormRules.normalize("", "new-secret") }
    }

    @Test fun controlCharactersAreRejectedInsteadOfBeingSilentlyTrimmed() {
        assertThrows(IllegalArgumentException::class.java) { CredentialsFormRules.normalize("stored-app\n", "new-secret") }
    }
}
