@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.withId
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaOverlayDialog
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixPageBottomSpacer
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun ModelProviderDetailScreen(
    providerId: String? = null,
    newType: NewProviderType? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    var createdId by remember { mutableStateOf<String?>(null) }
    val effectiveId = providerId ?: createdId
    val provider = remember(providers, effectiveId) {
        effectiveId?.let { id -> providers.firstOrNull { it.id == id } }
    }
    val draft = remember(newType) {
        when (newType) {
            NewProviderType.OpenAiCompatible -> CustomProviderSetting(
                id = "",
                name = "",
                baseUrl = "",
                endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            )
            NewProviderType.Anthropic -> AnthropicProviderSetting(
                id = "",
                name = "",
                baseUrl = "https://api.anthropic.com",
            )
            null -> null
        }
    }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    if (provider == null && draft == null) {
        MiuixScaffoldPage(
            title = stringResource(R.string.route_provider_details),
            onBack = onBack,
        ) {
            item(key = "missing_provider") {
                Column(
                    modifier = Modifier.fillParentMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.ui_provider_does_not_exist_83cee6))
                    Spacer(modifier = Modifier.height(12.dp))
                    EtaTextButton(text = stringResource(R.string.ui_return_11d024), onClick = onBack)
                }
            }
        }
        return
    }

    val initial = provider ?: draft!!
    val isNew = provider == null
    var currentTab by remember { mutableIntStateOf(0) }
    var configDraft by rememberSaveable(
        initial.id,
        stateSaver = ProviderConfigDraftSaver,
    ) {
        mutableStateOf(ProviderConfigDraft.from(initial))
    }
    val title = if (isNew) context.getString(R.string.page_create_new_provider_36cab9) else initial.name

    MiuixScaffold(title = title, onBack = onBack) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            if (!isNew) {
                TabRow(
                    tabs = listOf(context.getString(R.string.page_configuration_d7d7ce), context.getString(R.string.page_model_98fd0c)),
                    selectedTabIndex = currentTab,
                    onTabSelected = { currentTab = it },
                    modifier = Modifier.padding(
                        horizontal = sidePadding + EtaPreferenceDefaults.SidePadding,
                        vertical = 8.dp,
                    ),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    0 -> ProviderConfigTab(
                        provider = initial,
                        draft = configDraft,
                        onDraftChange = { configDraft = it },
                        scope = scope,
                        isNew = isNew,
                        scrollBehavior = scrollBehavior,
                        contentSidePadding = sidePadding,
                        onCreated = { id -> createdId = id },
                        onDeleted = onBack,
                    )
                    1 -> if (!isNew) {
                        ProviderModelsTab(
                            provider = initial,
                            scope = scope,
                            scrollBehavior = scrollBehavior,
                            contentSidePadding = sidePadding,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigTab(
    provider: ProviderSetting,
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    scope: CoroutineScope,
    isNew: Boolean,
    scrollBehavior: ScrollBehavior,
    contentSidePadding: Dp,
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    var confirmV4 by remember(provider.id) { mutableStateOf(false) }
    io.github.mangi.eta.ui.components.EtaWindowDialog(
        show = confirmV4,
        title = stringResource(R.string.context_recovery_enable_v4),
        summary = stringResource(R.string.context_recovery_legacy_warning),
        onDismissRequest = { confirmV4 = false },
    ) {
        EtaTextButton(stringResource(R.string.context_recovery_enable_v4), {
            confirmV4 = false
            onDraftChange(draft.copy(sendPolicyEnabled = true))
        })
        EtaTextButton(stringResource(R.string.context_recovery_back), { confirmV4 = false })
    }
    val context = LocalContext.current
    var headersExpanded by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var creationCommitted by remember { mutableStateOf(false) }
    // 只恢复中断提示；回调监听、验证码和授权 URL 随登录会话销毁。
    var loginInProgress by rememberSaveable(provider.id) { mutableStateOf(false) }
    var pendingInterruption by remember(provider.id) { mutableStateOf(loginInProgress) }
    val loginStore = remember(provider.id, scope) { ProviderLoginStore(provider.id, scope) }
    val loginState by loginStore.state.collectAsState()
    var browserError by remember(loginState.prompt) { mutableStateOf<String?>(null) }
    val openBrowser: () -> Unit = {
        loginState.prompt?.let { browserError = openProviderAccountLink(context, it.authorizationUrl) }
    }
    DisposableEffect(loginStore) {
        onDispose { loginStore.close() }
    }
    LaunchedEffect(loginStore, draft.authMode, provider.authMode) {
        loginStore.load(draft.authMode, interrupted = pendingInterruption)
        pendingInterruption = false
    }
    LaunchedEffect(loginState.phase) {
        loginInProgress = loginState.phase == ProviderLoginPhase.Saving || loginState.phase == ProviderLoginPhase.Authorizing
    }
    LaunchedEffect(loginState.prompt) {
        val prompt = loginState.prompt
        if (prompt != null && prompt.userCode == null) openBrowser()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // 低层 MiuixScaffold 只负责把顶栏 Insets 传给调用方，输入法 Insets 由列表自行消费。
            .imePadding()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = contentSidePadding,
            end = contentSidePadding,
        ),
        overscrollEffect = null,
    ) {
        item(key = "connection") {
            ProviderSection(title = stringResource(R.string.ui_connection_configuration_7d057b)) {
                ProviderConnectionFields(
                    provider = provider,
                    draft = draft,
                    onDraftChange = onDraftChange,
                    loginState = loginState,
                    enabled = !isWorking && !loginState.isBusy,
                    browserError = browserError,
                    onOpenBrowser = openBrowser,
                    onManageUsage = { browserError = openProviderAccountLink(context, "https://chatgpt.com/codex/settings/usage") },
                    onLogin = {
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            status = context.getString(R.string.provider_error, validationError)
                        } else {
                            loginStore.login(draft.toProvider(provider))
                        }
                    },
                    onDisconnect = loginStore::disconnect,
                    onCancel = loginStore::cancel,
                )
                if (draft.authMode == io.github.mangi.eta.data.model.ProviderAuthModes.OPENAI_CHATGPT) {
                    EtaTextButton(
                        text = "使用设备码登录",
                        enabled = !isWorking && !loginState.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { loginStore.login(draft.toProvider(provider), deviceCode = true) },
                    )
                }
                EtaPreferenceDivider(hasLeading = false)
                EtaPreference(
                    title = stringResource(R.string.ui_test_connection_10b7d8),
                    summary = testStatus,
                    enabled = !isWorking && !loginState.isBusy,
                    onClick = {
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            testStatus = context.getString(R.string.provider_error, validationError)
                            return@EtaPreference
                        }
                        scope.launch {
                            isWorking = true
                            testStatus = context.getString(R.string.page_testing_f43705)
                            try {
                                testStatus = testConnection(
                                    context,
                                    draft.toProvider(provider)
                                )
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                )
            }
        }

        providerHeadersEditor(
            headers = draft.headers,
            expanded = headersExpanded,
            onExpandedChange = { headersExpanded = it },
            onHeadersChange = { onDraftChange(draft.copy(headers = it)) },
        )

        item(key = "request_byte_budget") {
            ProviderSection(title = stringResource(R.string.provider_request_byte_budget_title)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = draft.requestByteBudgetKiB,
                        onValueChange = { onDraftChange(draft.copy(requestByteBudgetKiB = it)) },
                        label = "KiB",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = if (draft.sendPolicyEnabled) "v4 的软预算 B：留空不派生字节压缩线；0 关闭字节软触发；正数是自定义软阈值。发送边界 L/H 和 token 容量保护仍独立生效。" else stringResource(R.string.provider_request_byte_budget_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.provider_request_byte_budget_summary),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (requestByteBudgetInputError(draft.requestByteBudgetKiB)) {
                        Text(
                            text = stringResource(R.string.provider_request_byte_budget_error),
                            style = MiuixTheme.textStyles.footnote2,
                            color = StatusError,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        item(key = "request_send_policy") {
            ProviderSection(title = "实验性请求发送策略 v4") {
                EtaSwitchPreference(
                    title = "启用此服务商路由的发送边界",
                    checked = draft.sendPolicyEnabled,
                    onCheckedChange = { if (it) confirmV4 = true else onDraftChange(draft.copy(sendPolicyEnabled = false)) },
                )
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.context_recovery_legacy_warning), style = MiuixTheme.textStyles.footnote2)
                    Text(stringResource(R.string.context_recovery_headroom), style = MiuixTheme.textStyles.footnote2)
                    Text(
                        text = "默认关闭，保留旧策略。启用后，原请求大小软预算 B 仍独立触发压缩；B 留空不再派生字节压缩线。发送边界 L 只限制自动发送，不代表接口保证。适用于此服务商全部模型；模型自定义请求头或请求体时不启用。修改地址、协议、凭据或请求覆盖会使旧边界失效，需重新开启保存。",
                        style = MiuixTheme.textStyles.footnote2,
                    )
                    if (requestSendPolicyNeedsBootstrapReactivation(provider)) {
                        Text(
                            text = "此前保存的 bootstrap 发送策略已失效，不会继承旧的大边界。当前沿用旧策略，不受此 L/H 保护；请明确重新开启并保存，建立独立固定的 512 KiB bootstrap。真实成功观测值的已冻结边界不会因此缩小。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = StatusError,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (draft.sendPolicyEnabled) {
                        val excludedModels = requestSendPolicyExcludedModelNames(provider)
                        if (excludedModels.isNotEmpty()) {
                            Text(
                                text = "以下模型含独立请求覆盖，将沿用旧策略，不受此 L/H 发送边界保护：${excludedModels.joinToString("、")}。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = StatusError,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        TextField(
                            value = draft.observedRequestBytes,
                            onValueChange = { onDraftChange(draft.copy(observedRequestBytes = it)) },
                            label = "已确认成功的请求大小（字节，可留空）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        TextField(
                            value = draft.hardRequestBytes,
                            onValueChange = { onDraftChange(draft.copy(hardRequestBytes = it)) },
                            label = "已知接口硬限制 H（字节，未知留空）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Text(
                            text = "请只填写有证据的数值。观测值不是接口最大容量；留空时使用独立固定的 512 KiB bootstrap，与软预算 B 和模型窗口无关；无 H 时发送边界为 768 KiB。保存时冻结 L = anchor + 25%（增长余量最少 256 KiB、最多 512 KiB），且不超过已知 H。增长区间未经上传验证；后续成功不会自动抬高边界。",
                            style = MiuixTheme.textStyles.footnote2,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        val preview = runCatching {
                            buildUpdatedProvider(provider, draft.name, draft.baseUrl, draft.apiKey,
                                draft.systemPrompt, draft.isEnabled, draft.endpointMode,
                                draft.hostedWebSearchEnabled, draft.anthropicVersion,
                                draft.headers.map { it.header }, requestByteBudgetFromKiB(draft.requestByteBudgetKiB),
                                true, positiveRequestBytes(draft.observedRequestBytes), positiveRequestBytes(draft.hardRequestBytes))
                                .requestSendPolicy
                        }.getOrNull()
                        if (preview != null) {
                            Text(
                                text = if (preview.version == 4) "冻结发送边界 L：${preview.sendLimitBytes} 字节；增长余量：${preview.growthAllowanceBytes} 字节。" else "路由配置已变化；本次保存会关闭旧策略，请保存后重新开启。",
                                style = MiuixTheme.textStyles.footnote2,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            val soft = runCatching { requestByteBudgetFromKiB(draft.requestByteBudgetKiB) }.getOrNull()
                            if (soft != null && soft > (preview.sendLimitBytes ?: Long.MAX_VALUE)) {
                                Text(text = "软预算 B 高于发送边界 L；发送边界会先触发。", style = MiuixTheme.textStyles.footnote2)
                            }
                        }
                        if (sendPolicyInputError(draft)) Text(text = "字节数必须为正整数，或留空。", color = StatusError)
                    }
                }
            }
        }

        item(key = "preferences_and_prompt") {
            ProviderSection(title = stringResource(R.string.ui_preferences_and_strategies_2abd3c)) {
                EtaSwitchPreference(
                    title = stringResource(R.string.ui_enable_this_provider_683a76),
                    checked = draft.isEnabled,
                    onCheckedChange = { onDraftChange(draft.copy(isEnabled = it)) }
                )
                EtaPreferenceDivider(hasLeading = false)
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = draft.systemPrompt,
                        onValueChange = { onDraftChange(draft.copy(systemPrompt = it)) },
                        label = stringResource(R.string.ui_system_prompt_word_193981),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        singleLine = false,
                    )
                    Text(
                        text = stringResource(R.string.ui_leave_blank_to_use_the_default_mobile_agent_prompt_w_21e7c8),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item(key = "actions") {
            // 操作分层：主按钮实心独占，次要操作降级为文字按钮，与弹窗按钮语言一致
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EtaTextButton(
                    text = when {
                        isWorking -> context.getString(R.string.page_saving_d70d42)
                        creationCommitted -> context.getString(R.string.page_created_62cfc5)
                        isNew -> context.getString(R.string.page_create_fcbd09)
                        else -> context.getString(R.string.page_save_configuration_817af1)
                    },
                    enabled = !isWorking && !loginState.isBusy && !creationCommitted,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            status = context.getString(R.string.provider_error, validationError)
                            return@EtaTextButton
                        }
                        scope.launch {
                            isWorking = true
                            val built = draft.toProvider(provider)
                            try {
                                if (isNew) {
                                    val added = ProviderRepository.addProvider(
                                        built.withId(ProviderRepository.newId())
                                    )
                                    if (added.isEnabled) {
                                        RuntimeConfigRepository.setSelectedProviderId(added.id)
                                    }
                                    RuntimeConfigRepository.syncToRemotePreferences(
                                        EtaApp.serviceInstance
                                    )
                                    status = context.getString(R.string.capability_provider_created)
                                    creationCommitted = true
                                    onCreated(added.id)
                                } else {
                                    ProviderRepository.updateProvider(built)
                                    if (built.isEnabled) {
                                        RuntimeConfigRepository.setSelectedProviderId(built.id)
                                    }
                                    RuntimeConfigRepository.syncToRemotePreferences(
                                        EtaApp.serviceInstance
                                    )
                                    status = when {
                                        !built.isEnabled -> context.getString(R.string.page_saved_provider_not_enabled_7afa54)
                                        else -> context.getString(R.string.capability_provider_saved)
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (throwable: Throwable) {
                                status = context.getString(
                                    R.string.provider_error,
                                    throwable.message ?: context.getString(R.string.provider_save_failed),
                                )
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                )
                status?.let { message ->
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (message.startsWith(context.getString(R.string.page_fail_3e3c80))) StatusError else StatusSuccess,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (!isNew) {
            item(key = "danger_zone") {
                EtaCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                    showIndication = true,
                    onClick = if (isWorking || loginState.isBusy) {
                        null
                    } else {
                        {
                            if (provider.isBuiltIn) showResetDialog = true else showDeleteDialog = true
                        }
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (provider.isBuiltIn) context.getString(R.string.page_reset_built_in_configuration_35b6ec) else context.getString(R.string.page_remove_provider_9f848f),
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") {
            MiuixPageBottomSpacer()
        }
    }

    if (showDeleteDialog) {
        EtaOverlayDialog(
            show = true,
            title = stringResource(R.string.ui_remove_provider_9f848f),
            summary = stringResource(R.string.provider_delete_summary, provider.name),
            onDismissRequest = { if (!isWorking) showDeleteDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = if (isWorking) context.getString(R.string.page_deleting_6f941d) else context.getString(R.string.page_delete_3755f5),
                cancelEnabled = !isWorking,
                confirmEnabled = !isWorking,
                destructive = true,
                onCancel = { showDeleteDialog = false },
                onConfirm = {
                    scope.launch {
                        isWorking = true
                        try {
                            ProviderRepository.deleteProvider(provider.id)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            showDeleteDialog = false
                            onDeleted()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            status = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_delete_failed),
                            )
                            showDeleteDialog = false
                        } finally {
                            isWorking = false
                        }
                    }
                },
            )
        }
    }

    if (showResetDialog) {
        EtaOverlayDialog(
            show = true,
            title = stringResource(R.string.ui_reset_built_in_configuration_35b6ec),
            summary = stringResource(R.string.provider_reset_summary, provider.name),
            onDismissRequest = { if (!isWorking) showResetDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = if (isWorking) context.getString(R.string.page_resetting_616090) else context.getString(R.string.page_reset_3d8134),
                cancelEnabled = !isWorking,
                confirmEnabled = !isWorking,
                // 重置会覆盖用户改过的连接与模型配置，与删除同属破坏性确认。
                destructive = true,
                onCancel = { showResetDialog = false },
                onConfirm = {
                    scope.launch {
                        isWorking = true
                        try {
                            ProviderRepository.resetBuiltIn(provider.id)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            status = context.getString(R.string.page_reset_a0cc65)
                            showResetDialog = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            status = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_reset_failed),
                            )
                            showResetDialog = false
                        } finally {
                            isWorking = false
                        }
                    }
                },
            )
        }
    }
}

private suspend fun testConnection(
    context: android.content.Context,
    provider: ProviderSetting,
): String =
    RemoteModelFetcher.fetch(provider)
        .map { context.resources.getQuantityString(R.plurals.provider_models_fetched, it.size, it.size) }
        .getOrElse { throwable ->
            context.getString(
                R.string.provider_error,
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }
