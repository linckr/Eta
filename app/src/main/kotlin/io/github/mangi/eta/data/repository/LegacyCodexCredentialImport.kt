package io.github.mangi.eta.data.repository

import android.content.Context
import com.nimbusds.jwt.SignedJWT
import io.github.mangi.eta.agent.auth.CodexOAuth
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.agent.auth.ProviderCredentialStorage
import io.github.mangi.eta.agent.model.CodexCredentialStore
import io.github.mangi.eta.agent.model.CodexCredentials
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.BuiltinProviders

/** Read locally authenticated old ciphertext once, then retain it only as an inert recovery copy. */
internal object LegacyCodexCredentialImport {
    fun importOnce(context: Context, storage: ProviderCredentialStorage) {
        val prefs = context.getSharedPreferences("eta_credential_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("codex_v1_retired", false)) return
        val legacy = CodexCredentialStore(context).read() ?: return
        val converted = convert(legacy) ?: return
        val ids = mutableSetOf(BuiltinProviders.OPENAI_CODEX_ID)
        io.github.mangi.eta.data.db.EtaDatabase.get(context).openHelper.readableDatabase.query(
            "SELECT id FROM model_providers WHERE auth_mode='openai_chatgpt' AND " +
                "LOWER(RTRIM(base_url, '/')) = 'https://chatgpt.com/backend-api/codex'"
        ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getString(0) }
        for (providerId in ids) {
            // A pre-existing new-format login is authoritative and must never be overwritten.
            if (storage.read(providerId) != null) continue
            storage.write(providerId, converted)
            check(storage.read(providerId) == converted) { "Credential import verification failed" }
        }
        check(prefs.edit().putBoolean("codex_v1_retired", true).commit())
    }

    internal fun convert(legacy: CodexCredentials): ProviderCredential? {
        val claims = runCatching { SignedJWT.parse(legacy.accessToken).jwtClaimsSet }.getOrNull()
        val account = legacy.accountId?.takeIf { it.isNotBlank() }
            ?: runCatching { CodexOAuth.accountId(legacy.accessToken) }.getOrNull() ?: return null
        return ProviderCredential(
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
            clientId = CodexOAuth.CLIENT_ID,
            subject = claims?.subject ?: account,
            email = runCatching { claims?.getStringClaim("email") }.getOrNull(),
            accountId = account,
            accessToken = legacy.accessToken,
            refreshToken = legacy.refreshToken.orEmpty(),
            expiresAt = legacy.expiresAtEpochMillis ?: claims?.expirationTime?.time ?: Long.MAX_VALUE,
            scopes = CodexOAuth.SCOPE.split(' ').toSet(),
        )
    }
}
