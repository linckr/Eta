package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.CodexCredentials
import io.github.mangi.eta.agent.auth.CodexOAuth
import io.github.mangi.eta.data.model.ProviderAuthModes
import org.junit.Assert.*
import org.junit.Test

class LegacyCodexCredentialImportTest {
    @Test fun trustedLegacyAccountMapsToNewOwnerWithoutLosingTokens() {
        val old = CodexCredentials("opaque-fixture", "refresh-fixture", 123456L, "workspace")
        val mapped = requireNotNull(LegacyCodexCredentialImport.convert(old))
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, mapped.authMode)
        assertEquals(CodexOAuth.CLIENT_ID, mapped.clientId)
        assertEquals("workspace", mapped.subject)
        assertEquals("workspace", mapped.accountId)
        assertEquals(old.accessToken, mapped.accessToken)
        assertEquals(old.refreshToken, mapped.refreshToken)
        assertEquals(123456L, mapped.expiresAt)
        assertTrue(mapped.planEnabled)
        assertFalse(mapped.toString().contains(old.accessToken))
    }
    @Test fun missingWorkspaceIsNotImportedAsUsableSubscription() {
        assertNull(LegacyCodexCredentialImport.convert(CodexCredentials("opaque-fixture", "refresh", 123L)))
    }
}
