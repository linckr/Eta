package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.withApiKey
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.agent.roleplay.RoleplayReplyRevision
import io.github.mangi.eta.agent.roleplay.UserPersona
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaBackupRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        runBlocking {
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setCompactionModelId(null)
            SettingsDataStore.setOfficialModelCatalogRevision(0)
        }
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
    }

    @Test
    fun compactionSelectionSurvivesBackupAndLegacyBackupRestoresFollowCurrent() = runBlocking {
        val model = io.github.mangi.eta.data.model.Model(
            "summary-model", "summary-api", "Summary", contextWindowOverride = 64_000)
        val provider = io.github.mangi.eta.data.model.CustomProviderSetting(
            "summary-provider", "Summary", "https://example.invalid", apiKey = "fixture", models = listOf(model))
        ProviderRepository.addProvider(provider)
        SettingsDataStore.setSelection(provider.id, model.id)
        SettingsDataStore.setCompactionModelId(model.id)
        val conversation = ConversationEntity("summary-backup", "Fixture", false, createdAt = 1L, updatedAt = 2L)
        val message = ConversationMessageEntity("summary-message", conversation.id, 0, "user", "fixture")
        EtaDatabase.get(context).conversationDao().replaceAll(listOf(conversation), listOf(message), emptyList(),
            ConversationStateEntity(selectedConversationId = conversation.id))
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val bytes = output.toByteArray()

        SettingsDataStore.setCompactionModelId("changed")
        EtaBackupRepository.import(context, ByteArrayInputStream(bytes))
        assertEquals(model.id, SettingsDataStore.settings().compactionModelId)
        assertEquals(provider.id, SettingsDataStore.settings().selectedProviderId)
        assertEquals(model.id, SettingsDataStore.settings().selectedModelId)
        assertEquals("fixture", ProviderRepository.providerById(provider.id)!!.apiKey)
        assertEquals(64_000, ProviderRepository.providerById(provider.id)!!.models.single().contextWindowOverride)
        assertEquals(listOf(message), EtaDatabase.get(context).conversationDao().messages())

        val legacy = JsonObject(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject - "compactionModelId")
        EtaBackupRepository.import(context, ByteArrayInputStream(legacy.toString().toByteArray()))
        assertEquals(null, SettingsDataStore.settings().compactionModelId)
        assertEquals(model.id, SettingsDataStore.settings().selectedModelId)
        assertEquals(listOf(message), EtaDatabase.get(context).conversationDao().messages())

        val followOutput = ByteArrayOutputStream()
        EtaBackupRepository.export(context, followOutput)
        SettingsDataStore.setCompactionModelId(model.id)
        EtaBackupRepository.import(context, ByteArrayInputStream(followOutput.toByteArray()))
        assertEquals(null, SettingsDataStore.settings().compactionModelId)
    }

    @Test
    fun requestByteBudgetSurvivesBackup() = runBlocking {
        val providers = listOf<io.github.mangi.eta.data.model.ProviderSetting>(
            io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting("budget-default", "Default", "https://example.invalid", apiKey = "fixture", requestByteBudget = null),
            io.github.mangi.eta.data.model.AnthropicProviderSetting("budget-off", "Disabled", "https://example.invalid", apiKey = "fixture", requestByteBudget = 0),
            io.github.mangi.eta.data.model.CustomProviderSetting("budget-custom", "Custom", "https://example.invalid", apiKey = "fixture", requestByteBudget = 262657),
        )
        providers.forEach { ProviderRepository.addProvider(it) }
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val dao = EtaDatabase.get(context).providerDao()
        for (provider in providers) {
            val row = dao.providerById(provider.id)!!.provider
            dao.updateProvider(row.copy(requestByteBudget = 1))
        }
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        for (provider in providers) {
            val restored = ProviderRepository.providerById(provider.id)!!
            assertEquals(provider.requestByteBudget, restored.requestByteBudget)
            assertEquals(provider.apiKey, restored.apiKey)
        }
    }

    @Test
    fun capacityMetadataAndManualOverridesSurviveExportImport() = runBlocking {
        val models = listOf(
            io.github.mangi.eta.data.model.Model("capacity-total", "total", "Total",
                contextWindow = 128_000, contextWindowKind = "total", inputTokenLimit = 96_000, contextWindowOverride = 64_000),
            io.github.mangi.eta.data.model.Model("capacity-input", "input", "Input",
                contextWindow = 96_000, contextWindowKind = "input", inputTokenLimit = 96_000),
            io.github.mangi.eta.data.model.Model("capacity-legacy", "legacy", "Legacy",
                contextWindow = 128_000, contextWindowOverride = 256_000),
        )
        val provider = io.github.mangi.eta.data.model.CustomProviderSetting(
            id = "capacity-provider", name = "Capacity", baseUrl = "https://example.invalid", models = models)
        ProviderRepository.addProvider(provider)
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        ProviderRepository.replaceModels(provider.id, emptyList())
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        val restored = ProviderRepository.providerById(provider.id)!!.models.associateBy { it.id }
        assertEquals(models.associateBy { it.id }, restored)
        assertEquals("unknown", restored.getValue("capacity-total").effectiveContextWindowKind)
        assertEquals("input", restored.getValue("capacity-input").effectiveContextWindowKind)
    }

    @Test
    fun noticeDiagnosticAndLegacyArraySurviveBackupExportImport() = runBlocking {
        val safe = io.github.mangi.eta.agent.runtime.ModelFailureDiagnostic.sanitize(
            """{"code":"MODEL_TLS_FAILED","phase":"connect","trace_id":"12345678-1234-1234-1234-123456789abc"}""")!!
        val conversation = ConversationEntity("diagnostic-backup", "诊断", false, createdAt = 1L, updatedAt = 2L)
        val notices = listOf(safe, "[]").mapIndexed { index, metadata ->
            ConversationMessageEntity("notice-$index", conversation.id, index, "system_notice", "runtime_failed",
                resultSummary = "连接失败", toolsJson = metadata)
        }
        EtaDatabase.get(context).conversationDao().replaceAll(listOf(conversation), notices, emptyList(),
            ConversationStateEntity(selectedConversationId = conversation.id))
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        EtaDatabase.get(context).conversationDao().replaceAll(emptyList(), emptyList(), emptyList(), null)
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        val restored = EtaDatabase.get(context).conversationDao().messages()
        assertEquals(listOf(safe, "[]"), restored.sortedBy { it.sortIndex }.map { it.toolsJson })
        assertEquals(2, restored.size)
    }

    @Test
    fun exportAndImportRestoresProvidersConversationsAndMemory() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.allProviders().first().withApiKey("sk-backup-test")
        ProviderRepository.updateProvider(provider)
        SettingsDataStore.setSelection(provider.id, provider.models.first().id)
        AgentMemoryRepository.replaceAll("# 核心记忆\n喜欢 Kotlin")

        val conversation = ConversationEntity(
            id = "conversation-backup",
            title = "备份会话",
            thinkingEnabled = true,
            createdAt = 1L,
            updatedAt = 2L,
        )
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = listOf(conversation),
            messages = listOf(
                ConversationMessageEntity(
                    id = "message-backup",
                    conversationId = conversation.id,
                    sortIndex = 0,
                    type = "user",
                    content = "保留这条消息",
                ),
            ),
            contextCheckpoints = listOf(
                ConversationContextCheckpointEntity(
                    conversationId = conversation.id,
                    historyJson = "[]",
                ),
            ),
            state = ConversationStateEntity(selectedConversationId = conversation.id),
        )

        val output = ByteArrayOutputStream()
        val exported = EtaBackupRepository.export(context, output)
        assertEquals(1, exported.conversationCount)
        assertTrue(exported.providerCount > 0)
        assertEquals("# 核心记忆\n喜欢 Kotlin", AgentMemoryRepository.snapshot().content)

        ProviderRepository.updateProvider(provider.withApiKey("changed"))
        AgentMemoryRepository.replaceAll("changed")
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = emptyList(),
            messages = emptyList(),
            contextCheckpoints = emptyList(),
            state = null,
        )

        val imported = EtaBackupRepository.import(
            context,
            ByteArrayInputStream(output.toByteArray()),
        )
        assertEquals(1, imported.conversationCount)
        assertEquals("# 核心记忆\n喜欢 Kotlin", AgentMemoryRepository.snapshot().content)
        assertEquals(
            "保留这条消息",
            EtaDatabase.get(context).conversationDao().messages().single().content,
        )
        val restoredSettings = SettingsDataStore.settings()
        assertEquals(provider.id, restoredSettings.selectedProviderId)
        assertEquals(provider.models.first().id, restoredSettings.selectedModelId)
        assertEquals("sk-backup-test", ProviderRepository.providerById(provider.id)?.apiKey)
        assertEquals(ModelSource.CATALOG, ProviderRepository.providerById(provider.id)?.models?.first()?.source)
    }

    @Test(expected = EtaBackupException::class)
    fun rejectsUnknownBackupFormatBeforeChangingData(): Unit = runBlocking {
        EtaBackupRepository.inspect(context,
            ByteArrayInputStream("{\"format\":\"other\",\"schemaVersion\":1,\"exportedAt\":0}".toByteArray())
        )
    }

    @Test
    fun roleplayBackupRestoresCardsPersonaAvatarMemoryAndLargeSessionMetadata() = runBlocking {
        CharacterRepository.initialize(context)
        val profile = CharacterRepository.create(CharacterCardCodec.create("角色").withEdits(description = "设定".repeat(40_000)))
        CharacterRepository.writeAvatar(profile.id, Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMwdgn9DwADRgHM73L1cQAAAABJRU5ErkJggg==",
        )).let { CharacterRepository.save(profile.copy(avatarPath = it)) }
        CharacterRepository.savePersona(UserPersona("旅人", "来自远方"))
        CharacterMemoryRepository.replaceAll(context, profile.id, "# 剧情\n到过山中")
        val binding = CharacterRepository.binding(profile.id)
        val revisions = Json.encodeToString(RoleplayMessageState(
            links = mapOf("role-message" to RoleplayMessageLink("role-message")),
            revisions = mapOf("role-message" to RoleplayReplyRevision("原版", listOf("原版", "版本".repeat(40_000)), 1)),
        ))
        EtaDatabase.get(context).conversationDao().insertConversations(listOf(
            ConversationEntity("role-session", "剧情", false, roleplayJson = Json.encodeToString(binding),
                revisionsJson = revisions, createdAt = 1, updatedAt = 1),
        ))
        EtaDatabase.get(context).conversationDao().insertMessages(listOf(
            ConversationMessageEntity("role-message", "role-session", 0, "assistant", "版本".repeat(40_000)),
        ))
        val output = ByteArrayOutputStream()
        assertEquals(1, EtaBackupRepository.export(context, output).characterCount)
        CharacterRepository.save(profile.copy(card = profile.card.withEdits(description = "改动")))
        CharacterMemoryRepository.replaceAll(context, profile.id, "改动")
        CharacterRepository.savePersona(UserPersona("其他", ""))
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertEquals("设定".repeat(40_000), CharacterRepository.get(profile.id)?.card?.description)
        assertEquals("旅人", CharacterRepository.persona().name)
        assertEquals("# 剧情\n到过山中", CharacterMemoryRepository.snapshot(context, profile.id).content)
        val row = EtaDatabase.get(context).conversationDao().conversationEntities().single()
        assertEquals(revisions, row.revisionsJson)
        val restoredBinding = Json.decodeFromString<RoleplayBinding>(row.roleplayJson)
        assertTrue(java.io.File(restoredBinding.avatarPath!!).isFile)
        assertEquals(CharacterRepository.get(profile.id)?.avatarPath, restoredBinding.avatarPath)
    }

    @Test
    fun versionOneBackupRemainsReadable() = runBlocking {
        val summary = EtaBackupRepository.inspect(context, ByteArrayInputStream(
            """{"format":"eta-backup","schemaVersion":1,"exportedAt":0}""".toByteArray(),
        ))
        assertEquals(0, summary.characterCount)
    }

    @Test
    fun oldBackupReceivesOnlyModelsAddedAfterItsCatalogRevision() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val providers = EtaDatabase.get(context).providerDao().providers().map { row ->
            val models = if (row.provider.id == BuiltinProviders.OPENAI_ID) {
                row.models.filterNot { it.modelId.startsWith("gpt-6-") || it.modelId == "gpt-5.6-terra" }
            } else {
                row.models
            }
            EtaBackupProvider(row.provider, models)
        }
        val oldBackup = EtaBackupDocument(exportedAt = 0, providers = providers)
        EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(oldBackup).toByteArray()))

        val restored = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!.models
        assertTrue(restored.any { it.modelId == "gpt-6-astra" })
        assertTrue(restored.none { it.modelId == "gpt-5.6-terra" })
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, SettingsDataStore.officialModelCatalogRevision())
    }

    @Test
    fun currentBackupKeepsDeletionOfNewCatalogModel() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val removed = provider.models.first { it.modelId == "gpt-6-sol" }
        ModelRepository.deleteModel(provider.id, removed.id)
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val document = Json.decodeFromString<EtaBackupDocument>(output.toString(Charsets.UTF_8.name()))
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, document.catalogRevision)

        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))

        assertTrue(ProviderRepository.providerById(provider.id)!!.models.none { it.modelId == removed.modelId })
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, SettingsDataStore.officialModelCatalogRevision())
    }

    @Test
    fun memoryWriteFailureRollsBackDatabaseAndEarlierMemoryFiles() = runBlocking {
        CharacterRepository.initialize(context)
        val first = CharacterRepository.create(CharacterCardCodec.create("原角色"))
        val blocked = CharacterRepository.create(CharacterCardCodec.create("阻塞角色"))
        CharacterMemoryRepository.replaceAll(context, first.id, "原剧情记忆")
        AgentMemoryRepository.replaceAll("原现实记忆")
        val database = EtaDatabase.get(context)
        val records = database.characterDao().characters().map { row ->
            if (row.id == first.id) row.copy(cardJson = CharacterCardCodec.encodeJson(first.card.withEdits(name = "备份角色"))) else row
        }
        val document = EtaBackupDocument(exportedAt = 0, memoryMd = "新的现实记忆",
            roleplay = CharacterBackupData(characters = records, assets = listOf(
                CharacterBackupAsset(first.id, memoryMd = "新的剧情记忆"),
                CharacterBackupAsset(blocked.id, memoryMd = "写入失败"),
            )),
        )
        val blocker = java.io.File(context.filesDir, "roleplay/${blocked.id}/memory")
        check(blocker.parentFile!!.mkdirs() || blocker.parentFile!!.isDirectory)
        blocker.writeText("阻止创建目录")
        try {
            var failed = false
            try {
                EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()))
            } catch (_: AgentMemoryException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals("原角色", CharacterRepository.get(first.id)?.card?.name)
            assertEquals("原剧情记忆", CharacterMemoryRepository.snapshot(context, first.id).content)
            assertEquals("原现实记忆", AgentMemoryRepository.snapshot().content)
        } finally {
            check(blocker.delete())
        }
    }

    @Test
    fun invalidRevisionBackupIsRejectedBeforeExistingConversationsAreChanged() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        val original = ConversationEntity("original", "原会话", false, createdAt = 1, updatedAt = 1)
        dao.insertConversations(listOf(original))
        val invalidStates = listOf(
            "not-json",
            Json.encodeToString(RoleplayMessageState(
                links = mapOf("reply" to RoleplayMessageLink("reply")),
                revisions = mapOf("reply" to RoleplayReplyRevision("原版", listOf("原版"), 9)),
            )),
            Json.encodeToString(RoleplayMessageState(pendingRewrites = mapOf("run" to "outside-conversation"))),
        )
        invalidStates.forEach { revisions ->
            val document = EtaBackupDocument(exportedAt = 0,
                conversations = listOf(ConversationEntity("new", "导入会话", false, revisionsJson = revisions, createdAt = 1, updatedAt = 1)),
                messages = listOf(ConversationMessageEntity("reply", "new", 0, "assistant", "原版")),
            )
            var failed = false
            try {
                EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()))
            } catch (_: EtaBackupException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(listOf("original"), dao.conversationEntities().map { it.id })
        }
    }
}
