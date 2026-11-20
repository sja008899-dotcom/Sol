package com.example.sol.dialogue

import com.example.sol.brain.ClarifyIntentOption
import com.example.sol.brain.EntityExtractor
import com.example.sol.brain.HybridNluEngine
import com.example.sol.brain.NluDecision
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.PendingSolTask
import com.example.sol.brain.SolAction
import com.example.sol.brain.StatisticalIntentClassifier
import com.example.sol.brain.skills.SkillRegistry
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.contacts.ContactManager
import com.example.sol.contacts.ContactResolutionResult
import com.example.sol.contacts.ResolvedContact
import com.example.sol.memory.UserUtteranceRepository
import com.example.sol.text.PersianNumbers
import com.example.sol.text.PersianTextNormalizer

class DialogueManager(
    private val hybridNlu: HybridNluEngine,
    private val statisticalClassifier: StatisticalIntentClassifier,
    private val userUtteranceRepository: UserUtteranceRepository,
    private val contactManager: ContactManager,
    private val skillRegistry: SkillRegistry,
    private val extractor: EntityExtractor = EntityExtractor(),
) {
    private val recentTurns = ArrayDeque<DialogueTurn>(MAX_TURNS)

    @Volatile
    private var pendingState: PendingDialogueState? = null

    @Volatile
    private var lastSolReply: String = ""

    fun currentPendingState(): PendingDialogueState? = pendingState

    fun getRecentTurns(): List<DialogueTurn> = recentTurns.toList()

    fun clearPendingState() {
        pendingState = null
    }

    fun clearHistory() {
        recentTurns.clear()
        pendingState = null
        lastSolReply = ""
    }

    suspend fun handleTurn(rawInput: String): DialogueResult {
        statisticalClassifier.ensureInitialized { userUtteranceRepository.getAllOnce() }

        val cleanInput = PersianTextNormalizer.cleanDisplayText(rawInput)
        val normInput = PersianTextNormalizer.normalizeForMatching(cleanInput)

        // 1. Repeat command check ("دوباره بگو" / "چی گفتی؟")
        if (isRepeatPhrase(normInput)) {
            val reply = lastSolReply.ifBlank { "هنوز پاسخی نداده‌ام که تکرار کنم." }
            return DialogueResult(
                replyText = reply,
                parsedCommand = dummyCommand(SolAction.UNKNOWN, rawInput, normInput),
                speakReply = true,
            )
        }

        // 2. Explicit intent teaching correction ("نه، منظورم ... بود")
        val teachingCorrection = parseTeachingCorrection(cleanInput, normInput)
        if (teachingCorrection != null) {
            return handleTeachingCorrection(rawInput, normInput, teachingCorrection)
        }

        // 3. Cancellation check ("نه / بیخیال / لغو")
        val currentPending = pendingState
        if (isCancelPhrase(normInput)) {
            if (currentPending != null) {
                pendingState = null
                return finishTurn(
                    rawInput = rawInput,
                    normInput = normInput,
                    command = dummyCommand(SolAction.UNKNOWN, rawInput, normInput),
                    replyText = "باشه، لغو شد.",
                )
            }
        }

        // 4. Handle active multi-turn pending states
        if (currentPending != null) {
            when (currentPending) {
                is PendingDialogueState.AwaitingConfirmation -> {
                    if (isAffirmativePhrase(normInput)) {
                        return executeConfirmedAction(rawInput, normInput, currentPending)
                    }
                    // If user didn't confirm or cancel, check if they typed a completely new command
                    if (normInput.split(' ').size <= 2) {
                        return DialogueResult(
                            replyText = "لطفاً برای تأیید «تأیید» یا «بله» و برای انصراف «لغو» یا «نه» بگویید.",
                            parsedCommand = currentPending.command,
                            actionChoices = buildConfirmChoices(currentPending.kind),
                        )
                    } else {
                        // Treat longer new utterance as a fresh turn overwriting old unconfirmed prompt
                        pendingState = null
                    }
                }

                is PendingDialogueState.AwaitingContactChoice -> {
                    val chosen = resolveSelectedContactFromTurn(cleanInput, normInput, currentPending.candidates)
                    if (chosen != null) {
                        pendingState = null
                        val updatedCmd = currentPending.partialCommand.copy(
                            contact = chosen.name,
                            phone = chosen.phone.ifBlank { currentPending.partialCommand.phone },
                        )
                        return proceedAfterSlotsReady(updatedCmd, preResolvedContact = chosen)
                    }
                    return DialogueResult(
                        replyText = "متوجه نشدم؛ لطفاً شمارهٔ گزینه (مثلاً ۱ یا ۲) یا نام کامل مخاطب را بگویید، یا «لغو» بزنید.",
                        parsedCommand = currentPending.partialCommand,
                        actionChoices = buildContactUiChoices(currentPending.candidates),
                    )
                }

                is PendingDialogueState.AwaitingIntentClarification -> {
                    val chosenOption = resolveClarifiedIntentOption(cleanInput, normInput, currentPending.options)
                    if (chosenOption != null) {
                        pendingState = null
                        val savedExample = userUtteranceRepository.saveExample(
                            intent = chosenOption.action.name,
                            text = currentPending.originalText,
                        )
                        statisticalClassifier.addUserExample(savedExample)
                        val executed = proceedAfterSlotsReady(chosenOption.command)
                        return executed.copy(
                            replyText = "یاد گرفتم! از این به بعد شبیه این جمله را «${chosenOption.labelFa}» می‌فهمم.\n\n${executed.replyText}",
                            taughtExampleSaved = true,
                        )
                    }
                    // If user gave a full new command instead of picking 1/2, clear clarification and run normally
                    if (normInput.split(' ').size <= 2) {
                        return DialogueResult(
                            replyText = currentPending.promptFa,
                            parsedCommand = dummyCommand(SolAction.UNKNOWN, rawInput, normInput),
                            actionChoices = buildClarifyUiChoices(currentPending.options),
                        )
                    } else {
                        pendingState = null
                    }
                }

                is PendingDialogueState.MissingSlot -> {
                    pendingState = null
                    val filledCmd = mergeMissingSlot(currentPending, rawInput, cleanInput, normInput)
                    if (filledCmd != null) {
                        return proceedAfterSlotsReady(filledCmd)
                    }
                }
            }
        }

        // 5. Fresh NLU turn with Confidence Gate
        return when (val decision = hybridNlu.evaluate(rawInput)) {
            is NluDecision.ClarifyTopTwo -> {
                val labelsPrompt = buildString {
                    append("مطمئن نیستم دقیقاً کدام کار را می‌خواهید؛ لطفاً انتخاب کنید:\n")
                    decision.options.take(2).forEachIndexed { idx, opt ->
                        val persianNum = if (idx == 0) "۱" else "۲"
                        append("$persianNum. ${opt.labelFa}\n")
                    }
                    append("یا اگر هیچ‌کدام نیست، بنویسید «نه، منظورم … بود».")
                }.trim()
                pendingState = PendingDialogueState.AwaitingIntentClarification(
                    originalText = rawInput,
                    options = decision.options.take(2),
                    promptFa = labelsPrompt,
                )
                lastSolReply = labelsPrompt
                DialogueResult(
                    replyText = labelsPrompt,
                    parsedCommand = decision.options.firstOrNull()?.command ?: dummyCommand(SolAction.UNKNOWN, rawInput, normInput),
                    actionChoices = buildClarifyUiChoices(decision.options.take(2)),
                    nluConfidenceLabel = decision.confidence.labelFa,
                )
            }

            is NluDecision.LowConfidence -> {
                val unknownCmd = dummyCommand(SolAction.UNKNOWN, rawInput, decision.normalizedText)
                val resolvedCmd = resolvePronounsFromContext(unknownCmd)
                val skillRes = skillRegistry.execute(resolvedCmd)
                finishTurn(
                    rawInput = rawInput,
                    normInput = normInput,
                    command = resolvedCmd,
                    replyText = skillRes.replyText,
                    pendingTask = skillRes.pendingTask,
                    speakReply = skillRes.speakReply,
                    confidenceLabel = decision.confidence.labelFa,
                )
            }

            is NluDecision.Execute -> {
                val enrichedCmd = resolvePronounsFromContext(decision.command)
                proceedAfterSlotsReady(
                    command = enrichedCmd,
                    confidenceLabel = decision.confidence.labelFa,
                )
            }
        }
    }

    suspend fun requestBackupRestoreConfirmation(
        detailFa: String,
        onConfirmed: suspend () -> String,
    ): DialogueResult {
        val cmd = dummyCommand(SolAction.UNKNOWN, "بازیابی پشتیبان", "بازیابی پشتیبان").copy(requireConfirmation = true)
        pendingState = PendingDialogueState.AwaitingConfirmation(
            kind = ConfirmationKind.RESTORE_BACKUP,
            command = cmd,
            targetSummaryFa = detailFa,
            onConfirmedBackupCallback = onConfirmed,
        )
        val prompt = "آیا مطمئن هستید که فایل پشتیبان جایگزین/بازیابی شود؟\n$detailFa\nبرای ادامه «تأیید» یا برای لغو «لغو» را بزنید."
        lastSolReply = prompt
        return DialogueResult(
            replyText = prompt,
            parsedCommand = cmd,
            actionChoices = buildConfirmChoices(ConfirmationKind.RESTORE_BACKUP),
        )
    }

    private suspend fun proceedAfterSlotsReady(
        command: ParsedCommand,
        preResolvedContact: ResolvedContact? = null,
        confidenceLabel: String = "",
    ): DialogueResult {
        // Check missing required slots FIRST
        when (command.action) {
            SolAction.CALL, SolAction.SEND_SMS, SolAction.MULTI_STEP_NOTIFY -> {
                if (command.contact.isNullOrBlank() && command.phone.isNullOrBlank()) {
                    val prompt = when (command.action) {
                        SolAction.CALL -> "با چه کسی تماس بگیرم؟"
                        SolAction.SEND_SMS -> "به چه کسی پیام بدهم؟"
                        else -> "به چه کسی اطلاع بدهم؟"
                    }
                    pendingState = PendingDialogueState.MissingSlot(
                        action = command.action,
                        missingSlotName = "contact",
                        partialCommand = command,
                        promptFa = prompt,
                    )
                    lastSolReply = prompt
                    return DialogueResult(
                        replyText = prompt,
                        parsedCommand = command,
                        actionChoices = listOf(
                            DialogueUiChoice("cancel", "لغو", "لغو", isDestructive = true),
                        ),
                        nluConfidenceLabel = confidenceLabel,
                    )
                }

                // Ambiguous contact disambiguation (skip if already resolved or direct phone number)
                val targetQuery = command.contact.orEmpty().ifBlank { command.phone.orEmpty() }
                var resolvedContact = preResolvedContact
                val isDirectPhone = extractor.normalizeIranPhoneToE164(targetQuery).isValid
                if (resolvedContact == null && !isDirectPhone && targetQuery.isNotBlank()) {
                    when (val resolution = contactManager.resolveContact(targetQuery)) {
                        is ContactResolutionResult.Ambiguous -> {
                            val withPhone = resolution.candidates.filter { it.phone.isNotBlank() }
                            val listToChoose = if (withPhone.size > 1) withPhone else resolution.candidates
                            if (listToChoose.size > 1) {
                                val topList = listToChoose.take(4)
                                val prompt = buildString {
                                    append("چند مخاطب برای «$targetQuery» پیدا شد؛ کدام یک را انتخاب می‌کنید؟\n")
                                    topList.forEachIndexed { idx, c ->
                                        val phoneNote = if (c.phone.isNotBlank()) " (${c.phone})" else ""
                                        append("${toPersianDigit(idx + 1)}. ${c.name}$phoneNote\n")
                                    }
                                    append("شماره یا نام را بگویید یا دکمه را لمس کنید.")
                                }.trim()
                                pendingState = PendingDialogueState.AwaitingContactChoice(
                                    action = command.action,
                                    partialCommand = command,
                                    candidates = topList,
                                    promptFa = prompt,
                                )
                                lastSolReply = prompt
                                return DialogueResult(
                                    replyText = prompt,
                                    parsedCommand = command,
                                    actionChoices = buildContactUiChoices(topList),
                                    nluConfidenceLabel = confidenceLabel,
                                )
                            } else if (listToChoose.size == 1) {
                                resolvedContact = listToChoose.first()
                            }
                        }
                        is ContactResolutionResult.Resolved -> {
                            resolvedContact = resolution.contact
                        }
                        is ContactResolutionResult.NotFound -> Unit
                    }
                }

                val finalCommand = if (resolvedContact != null) {
                    command.copy(
                        contact = resolvedContact.name,
                        phone = resolvedContact.phone.ifBlank { command.phone },
                    )
                } else {
                    command
                }

                // Check if SEND_SMS is missing message body -> ask one question
                if (finalCommand.action == SolAction.SEND_SMS && finalCommand.message.isNullOrBlank()) {
                    val prompt = "چه پیامی برای ${finalCommand.contact} بنویسم؟"
                    pendingState = PendingDialogueState.MissingSlot(
                        action = SolAction.SEND_SMS,
                        missingSlotName = "message",
                        partialCommand = finalCommand,
                        promptFa = prompt,
                    )
                    lastSolReply = prompt
                    return DialogueResult(
                        replyText = prompt,
                        parsedCommand = finalCommand,
                        actionChoices = listOf(
                            DialogueUiChoice("cancel", "لغو", "لغو", isDestructive = true),
                        ),
                        nluConfidenceLabel = confidenceLabel,
                    )
                }

                // Centralized confirmation gate + Communication Task
                val skillRes = skillRegistry.execute(finalCommand)
                val commTask = skillRes.pendingTask as? PendingSolTask.Communicate
                val kind = when (finalCommand.action) {
                    SolAction.CALL -> ConfirmationKind.CALL
                    SolAction.SEND_SMS -> ConfirmationKind.SEND_SMS
                    else -> ConfirmationKind.MULTI_STEP_NOTIFY
                }
                if (commTask != null) {
                    pendingState = PendingDialogueState.AwaitingConfirmation(
                        kind = kind,
                        command = finalCommand,
                        targetSummaryFa = "${finalCommand.contact} — ${finalCommand.message.orEmpty()}",
                        communicateTask = commTask,
                    )
                }
                return finishTurn(
                    rawInput = finalCommand.rawText,
                    normInput = finalCommand.normalizedText,
                    command = finalCommand,
                    replyText = skillRes.replyText,
                    pendingTask = skillRes.pendingTask,
                    speakReply = skillRes.speakReply,
                    resolvedContact = resolvedContact,
                    actionChoices = if (commTask != null) buildConfirmChoices(kind) else emptyList(),
                    confidenceLabel = confidenceLabel,
                )
            }

            SolAction.SCHEDULE_REMINDER -> {
                if (!command.hasExplicitTimeSlot) {
                    val bodyLabel = command.message.orEmpty().ifBlank { "این مورد" }
                    val prompt = "برای چه زمان یا ساعتی یادآور «$bodyLabel» را تنظیم کنم؟ (مثلاً: فردا ساعت ۸ صبح یا ۱۰ دقیقه دیگه)"
                    pendingState = PendingDialogueState.MissingSlot(
                        action = SolAction.SCHEDULE_REMINDER,
                        missingSlotName = "datetime",
                        partialCommand = command,
                        promptFa = prompt,
                    )
                    lastSolReply = prompt
                    return DialogueResult(
                        replyText = prompt,
                        parsedCommand = command,
                        actionChoices = listOf(
                            DialogueUiChoice("cancel", "لغو", "لغو", isDestructive = true),
                        ),
                        nluConfidenceLabel = confidenceLabel,
                    )
                }
            }

            SolAction.FORGET_MEMORY -> {
                val target = command.aliasKey.orEmpty().trim()
                if (target.isBlank()) {
                    val prompt = "کدام مورد را از حافظه حذف کنم؟"
                    pendingState = PendingDialogueState.MissingSlot(
                        action = SolAction.FORGET_MEMORY,
                        missingSlotName = "aliasKey",
                        partialCommand = command,
                        promptFa = prompt,
                    )
                    lastSolReply = prompt
                    return DialogueResult(
                        replyText = prompt,
                        parsedCommand = command,
                        actionChoices = listOf(DialogueUiChoice("cancel", "لغو", "لغو", isDestructive = true)),
                        nluConfidenceLabel = confidenceLabel,
                    )
                }
                val prompt = "آیا مطمئن هستید که «$target» از حافظه حذف شود؟"
                pendingState = PendingDialogueState.AwaitingConfirmation(
                    kind = ConfirmationKind.FORGET_MEMORY,
                    command = command,
                    targetSummaryFa = target,
                )
                lastSolReply = prompt
                return DialogueResult(
                    replyText = prompt,
                    parsedCommand = command,
                    actionChoices = buildConfirmChoices(ConfirmationKind.FORGET_MEMORY),
                    nluConfidenceLabel = confidenceLabel,
                )
            }

            else -> Unit
        }

        val skillRes = skillRegistry.execute(command)
        return finishTurn(
            rawInput = command.rawText,
            normInput = command.normalizedText,
            command = command,
            replyText = skillRes.replyText,
            pendingTask = skillRes.pendingTask,
            speakReply = skillRes.speakReply,
            resolvedContact = preResolvedContact,
            confidenceLabel = confidenceLabel,
        )
    }

    private suspend fun executeConfirmedAction(
        rawInput: String,
        normInput: String,
        pending: PendingDialogueState.AwaitingConfirmation,
    ): DialogueResult {
        pendingState = null
        return when (pending.kind) {
            ConfirmationKind.FORGET_MEMORY -> {
                val res = skillRegistry.execute(pending.command)
                finishTurn(
                    rawInput = rawInput,
                    normInput = normInput,
                    command = pending.command,
                    replyText = res.replyText,
                    speakReply = res.speakReply,
                )
            }
            ConfirmationKind.RESTORE_BACKUP -> {
                val reply = pending.onConfirmedBackupCallback?.invoke()
                    ?: "بازیابی پشتیبان تأیید شد."
                finishTurn(
                    rawInput = rawInput,
                    normInput = normInput,
                    command = pending.command,
                    replyText = reply,
                )
            }
            ConfirmationKind.CALL, ConfirmationKind.SEND_SMS, ConfirmationKind.MULTI_STEP_NOTIFY -> {
                // Signal UI confirmation manager / reply cleanly
                val actionLabel = if (pending.kind == ConfirmationKind.CALL) "تماس" else "پیامک"
                finishTurn(
                    rawInput = rawInput,
                    normInput = normInput,
                    command = pending.command,
                    replyText = "تأیید $actionLabel با ${pending.targetSummaryFa} دریافت شد.",
                    pendingTask = pending.communicateTask,
                )
            }
        }
    }

    private fun resolvePronounsFromContext(command: ParsedCommand): ParsedCommand {
        if (recentTurns.isEmpty()) return command
        val norm = command.normalizedText
        val hasContactPronoun = Regex("(?:^|\\s)(?:به او|بهش|با او|باهاش|اون|او|همون|همین شخص)(?:\\s|$)").containsMatchIn(norm)
        val hasContentEllipsis = Regex("(?:^|\\s)(?:همین را|همین رو|همین|اون رو|اون را|این رو|این را)(?:\\s|$)").containsMatchIn(norm)

        var updated = command
        if ((updated.contact.isNullOrBlank() || isPronounToken(updated.contact)) && hasContactPronoun) {
            val prevContactTurn = recentTurns.lastOrNull { it.resolvedContact != null || !it.slotSummary["contact"].isNullOrBlank() }
            val resolvedName = prevContactTurn?.resolvedContact?.name ?: prevContactTurn?.slotSummary?.get("contact")
            val resolvedPhone = prevContactTurn?.resolvedContact?.phone ?: prevContactTurn?.slotSummary?.get("phone")
            if (!resolvedName.isNullOrBlank()) {
                updated = updated.copy(
                    contact = resolvedName,
                    phone = updated.phone ?: resolvedPhone,
                )
            }
        }

        if (hasContentEllipsis && (updated.action == SolAction.SCHEDULE_REMINDER || updated.action == SolAction.SEND_SMS || updated.action == SolAction.LEARN_FACT)) {
            val prevContentTurn = recentTurns.lastOrNull { !it.slotSummary["message"].isNullOrBlank() || it.userText.isNotBlank() }
            val prevMessage = prevContentTurn?.slotSummary?.get("message")?.takeIf { it.isNotBlank() }
                ?: prevContentTurn?.userText
            if (!prevMessage.isNullOrBlank() && (updated.message.isNullOrBlank() || updated.message?.contains("همین") == true)) {
                val nextSchedule = updated.jalaliSchedule?.copy(reminderMessage = prevMessage)
                updated = updated.copy(
                    message = prevMessage,
                    jalaliSchedule = nextSchedule,
                )
            }
        }
        return updated
    }

    private suspend fun mergeMissingSlot(
        pending: PendingDialogueState.MissingSlot,
        rawInput: String,
        cleanInput: String,
        normInput: String,
    ): ParsedCommand? {
        val base = pending.partialCommand
        return when (pending.missingSlotName) {
            "contact" -> {
                val phoneRes = extractor.extractValidatedPhone(cleanInput)
                val cleanedName = cleanInput
                    .replace(Regex("^(?:به|با|برای)\\s+"), "")
                    .trim()
                if (cleanedName.isBlank()) null
                else base.copy(
                    contact = phoneRes?.takeIf { it.isValid }?.e164 ?: cleanedName,
                    phone = phoneRes?.takeIf { it.isValid }?.e164 ?: base.phone,
                    rawText = "${base.rawText} ⟵ $rawInput",
                )
            }
            "message" -> {
                if (cleanInput.isBlank()) null
                else base.copy(
                    message = cleanInput,
                    rawText = "${base.rawText} ⟵ $rawInput",
                )
            }
            "datetime" -> {
                val parsedSchedule = JalaliCalendar.parseDateTimeSlot(rawInput)
                if (parsedSchedule != null) {
                    val preservedMsg = base.message?.takeIf { it.isNotBlank() } ?: parsedSchedule.reminderMessage
                    base.copy(
                        jalaliSchedule = parsedSchedule.copy(reminderMessage = preservedMsg),
                        message = preservedMsg,
                        hasExplicitTimeSlot = true,
                        rawText = "${base.rawText} ⟵ $rawInput",
                    )
                } else {
                    val minMatch = Regex("(\\d+)\\s*(?:دقیقه|ساعت)").find(PersianNumbers.replaceWordNumbersWithDigits(normInput))
                    if (minMatch != null) {
                        val qty = minMatch.groupValues[1].toLongOrNull() ?: 1L
                        val mins = if (normInput.contains("ساعت")) qty * 60L else qty
                        base.copy(
                            delayMinutes = mins.coerceAtLeast(1L),
                            hasExplicitTimeSlot = true,
                            rawText = "${base.rawText} ⟵ $rawInput",
                        )
                    } else null
                }
            }
            "aliasKey" -> {
                if (cleanInput.isBlank()) null
                else base.copy(aliasKey = cleanInput)
            }
            else -> null
        }
    }

    private suspend fun handleTeachingCorrection(
        rawInput: String,
        normInput: String,
        intendedText: String,
    ): DialogueResult {
        val targetAction = HybridNluEngine.resolveIntentFromUserText(intendedText)
            ?: hybridNlu.evaluate(intendedText).let {
                when (it) {
                    is NluDecision.Execute -> it.command.action
                    is NluDecision.ClarifyTopTwo -> it.options.firstOrNull()?.action
                    is NluDecision.LowConfidence -> null
                }
            }

        val previousUserUtterance = when (val p = pendingState) {
            is PendingDialogueState.AwaitingIntentClarification -> p.originalText
            else -> recentTurns.lastOrNull()?.userText
        }?.takeIf { it.isNotBlank() } ?: rawInput

        pendingState = null

        if (targetAction != null && targetAction != SolAction.UNKNOWN) {
            val saved = userUtteranceRepository.saveExample(
                intent = targetAction.name,
                text = previousUserUtterance,
            )
            statisticalClassifier.addUserExample(saved)
            val labelFa = HybridNluEngine.actionLabelFa(targetAction)
            val cmd = hybridNlu.buildStatisticalCommand(targetAction, previousUserUtterance, 0.95f)
            val reply = "متوجه شدم! عبارت «$previousUserUtterance» برای «$labelFa» ذخیره شد (در صفحهٔ حافظه قابل مشاهده و حذف است)."
            lastSolReply = reply
            return DialogueResult(
                replyText = reply,
                parsedCommand = cmd,
                taughtExampleSaved = true,
            )
        }

        val reply = "متوجه نشدم منظور شما دقیقاً کدام فرمان است (مثلاً تماس، پیامک، یادآور یا حافظه)؛ لطفاً واضح‌تر بنویسید."
        lastSolReply = reply
        return DialogueResult(
            replyText = reply,
            parsedCommand = dummyCommand(SolAction.UNKNOWN, rawInput, normInput),
        )
    }

    private fun finishTurn(
        rawInput: String,
        normInput: String,
        command: ParsedCommand,
        replyText: String,
        pendingTask: PendingSolTask? = null,
        speakReply: Boolean = true,
        resolvedContact: ResolvedContact? = null,
        actionChoices: List<DialogueUiChoice> = emptyList(),
        confidenceLabel: String = "",
    ): DialogueResult {
        val slots = buildMap<String, String> {
            command.contact?.let { put("contact", it) }
            command.phone?.let { put("phone", it) }
            command.message?.let { put("message", it) }
            command.locationReference?.let { put("location", it) }
        }
        val turn = DialogueTurn(
            userText = rawInput,
            normalizedText = normInput,
            action = command.action,
            resolvedContact = resolvedContact,
            slotSummary = slots,
            solReply = replyText,
        )
        if (recentTurns.size >= MAX_TURNS) {
            recentTurns.removeFirst()
        }
        recentTurns.addLast(turn)
        if (replyText.isNotBlank()) {
            lastSolReply = replyText
        }
        return DialogueResult(
            replyText = replyText,
            parsedCommand = command,
            pendingTask = pendingTask,
            speakReply = speakReply,
            actionChoices = actionChoices,
            nluConfidenceLabel = confidenceLabel,
        )
    }

    private fun resolveSelectedContactFromTurn(
        clean: String,
        norm: String,
        candidates: List<ResolvedContact>,
    ): ResolvedContact? {
        val numIdx = parseChoiceNumber(norm)
        if (numIdx != null && numIdx in 1..candidates.size) {
            return candidates[numIdx - 1]
        }
        return candidates.firstOrNull { c ->
            val cn = PersianTextNormalizer.normalizeForMatching(c.name)
            cn == norm || cn.contains(norm) || (c.phone.isNotBlank() && clean.contains(c.phone))
        }
    }

    private fun resolveClarifiedIntentOption(
        clean: String,
        norm: String,
        options: List<ClarifyIntentOption>,
    ): ClarifyIntentOption? {
        val numIdx = parseChoiceNumber(norm)
        if (numIdx != null && numIdx in 1..options.size) {
            return options[numIdx - 1]
        }
        return options.firstOrNull { opt ->
            val labelNorm = PersianTextNormalizer.normalizeForMatching(opt.labelFa)
            norm == labelNorm || labelNorm.contains(norm)
        }
    }

    private fun parseChoiceNumber(norm: String): Int? {
        val converted = PersianNumbers.replaceWordNumbersWithDigits(norm)
        val num = Regex("\\b([1-9])\\b").find(converted)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (num != null) return num
        return when {
            norm.contains("اول") || norm == "یک" -> 1
            norm.contains("دوم") || norm == "دو" -> 2
            norm.contains("سوم") || norm == "سه" -> 3
            norm.contains("چهارم") || norm == "چهار" -> 4
            else -> null
        }
    }

    private fun parseTeachingCorrection(clean: String, norm: String): String? {
        val m = Regex("(?:نه[,،]?\\s*)?(?:منظورم|قصدم|میخواستم)\\s+(.+?)(?:\\s+(?:بود|هست|است))?$").find(clean)
        val extracted = m?.groupValues?.getOrNull(1)?.trim()
        if (!extracted.isNullOrBlank()) return extracted
        if (norm.startsWith("منظورم ") || norm.startsWith("نه منظورم ")) {
            return norm.removePrefix("نه ").removePrefix("منظورم ").removeSuffix(" بود").trim().takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun isRepeatPhrase(norm: String): Boolean {
        return norm in setOf(
            "دوباره بگو", "یه بار دیگه بگو", "یک بار دیگه بگو", "تکرار کن",
            "چی گفتی", "نفهمیدم چی گفتی", "دوباره بخوان", "دوباره بخون",
        )
    }

    private fun isCancelPhrase(norm: String): Boolean {
        return norm in setOf(
            "نه", "نخیر", "بیخیال", "بی خیال", "لغو", "انصراف", "کنسل",
            "لغو کن", "نمیخوام", "نمی خواهم", "خیر", "ولش کن",
        )
    }

    private fun isAffirmativePhrase(norm: String): Boolean {
        return norm in setOf(
            "بله", "اره", "آره", "تایید", "تأیید", "باشه", "حتما", "اوکی",
            "بله تایید", "انجام بده", "بفرست", "بزن", "بله حذف کن",
        )
    }

    private fun isPronounToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return true
        val n = PersianTextNormalizer.normalizeForMatching(token)
        return n in setOf("او", "اون", "بهش", "باهاش", "همون", "ایشان", "وی")
    }

    private fun buildConfirmChoices(kind: ConfirmationKind): List<DialogueUiChoice> {
        val yesLabel = when (kind) {
            ConfirmationKind.FORGET_MEMORY -> "بله، حذف کن"
            ConfirmationKind.RESTORE_BACKUP -> "بله، بازیابی شود"
            ConfirmationKind.CALL -> "تأیید تماس"
            ConfirmationKind.SEND_SMS -> "تأیید ارسال پیامک"
            ConfirmationKind.MULTI_STEP_NOTIFY -> "تأیید اجرای مراحل"
        }
        return listOf(
            DialogueUiChoice("confirm_yes", yesLabel, "تأیید", isPrimary = true),
            DialogueUiChoice("confirm_no", "لغو", "لغو", isDestructive = true),
        )
    }

    private fun buildContactUiChoices(candidates: List<ResolvedContact>): List<DialogueUiChoice> {
        return candidates.mapIndexed { idx, c ->
            DialogueUiChoice(
                id = "contact_${c.id}",
                labelFa = "${toPersianDigit(idx + 1)}. ${c.name}",
                submitText = "${idx + 1}",
                isPrimary = idx == 0,
            )
        } + DialogueUiChoice("cancel", "لغو", "لغو", isDestructive = true)
    }

    private fun buildClarifyUiChoices(options: List<ClarifyIntentOption>): List<DialogueUiChoice> {
        return options.mapIndexed { idx, opt ->
            DialogueUiChoice(
                id = "clarify_${opt.action.name}",
                labelFa = "${toPersianDigit(idx + 1)}. ${opt.labelFa}",
                submitText = "${idx + 1}",
                isPrimary = idx == 0,
            )
        } + DialogueUiChoice("cancel", "لغو", "لغو", isDestructive = true)
    }

    private fun toPersianDigit(n: Int): String = when (n) {
        1 -> "۱"
        2 -> "۲"
        3 -> "۳"
        4 -> "۴"
        else -> n.toString()
    }

    private fun dummyCommand(action: SolAction, raw: String, norm: String): ParsedCommand = ParsedCommand(
        action = action,
        rawText = raw,
        normalizedText = norm,
        requireConfirmation = false,
    )

    companion object {
        const val MAX_TURNS = 5
    }
}
