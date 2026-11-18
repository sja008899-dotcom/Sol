package com.example.sol.tasks

import com.example.sol.brain.PendingSolTask
import com.example.sol.communication.CallManager
import com.example.sol.communication.CallScriptManager
import com.example.sol.communication.MessageManager
import com.example.sol.contacts.ContactManager
import com.example.sol.contacts.ResolvedContact
import com.example.sol.memory.DeliveryPreference
import com.example.sol.memory.HistoryRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TaskStepStatus {
    PENDING,
    RUNNING,
    WAITING_CONFIRMATION,
    DONE,
    FAILED,
}

data class TaskStepItem(
    val index: Int,
    val titleFa: String,
    val detailFa: String = "",
    val status: TaskStepStatus = TaskStepStatus.PENDING,
)

data class ActiveTaskState(
    val titleFa: String,
    val steps: List<TaskStepItem>,
    val isComplete: Boolean = false,
)

class TaskManager(
    private val contactManager: ContactManager,
    private val locationRepository: LocationRepository,
    private val preferencesRepository: PreferencesRepository,
    private val callScriptManager: CallScriptManager,
    private val callManager: CallManager,
    private val messageManager: MessageManager,
    private val historyRepository: HistoryRepository,
    val confirmationManager: ConfirmationManager,
) {
    private val _activeTask = MutableStateFlow<ActiveTaskState?>(null)
    val activeTask: StateFlow<ActiveTaskState?> = _activeTask.asStateFlow()

    suspend fun startPendingTask(
        task: PendingSolTask.Communicate,
        onStatusMessage: (String) -> Unit,
    ) {
        val prefs = preferencesRepository.getSnapshot()
        val steps = mutableListOf(
            TaskStepItem(1, "یافتن اطلاعات مخاطب (${task.contactQuery})", status = TaskStepStatus.RUNNING),
            TaskStepItem(2, "بررسی موقعیت فعلی تأییدشده", status = TaskStepStatus.PENDING),
            TaskStepItem(3, "ساخت متن با معرفی شفاف Sol", status = TaskStepStatus.PENDING),
            TaskStepItem(4, "دریافت تأیید صریح کاربر", status = TaskStepStatus.PENDING),
            TaskStepItem(5, "اجرای اقدام ارتباطی مجاز", status = TaskStepStatus.PENDING),
            TaskStepItem(6, "ثبت گزارش نتیجه در سوابق", status = TaskStepStatus.PENDING),
        )
        if (task.isMultiStepReport) {
            _activeTask.value = ActiveTaskState(
                titleFa = "فرمان چندمرحله‌ای برای ${task.contactQuery}",
                steps = steps,
            )
        }

        // Step 1: Contact lookup
        val matches = contactManager.findMatchingContacts(task.contactQuery)
            .filter { it.phone.isNotBlank() }
        if (matches.isEmpty()) {
            val permissionHint = if (!contactManager.hasContactsPermission()) {
                " (مجوز دسترسی به مخاطبین داده نشده و در حافظهٔ Sol هم شماره‌ای ثبت نیست)"
            } else {
                ""
            }
            val failMsg = "مخاطبی با شمارهٔ معتبر برای «${task.contactQuery}» پیدا نکردم$permissionHint."
            if (task.isMultiStepReport) {
                steps[0] = steps[0].copy(status = TaskStepStatus.FAILED, detailFa = failMsg)
                _activeTask.value = _activeTask.value?.copy(steps = steps.toList(), isComplete = true)
            }
            onStatusMessage(failMsg)
            return
        }

        val primary = matches.first()
        steps[0] = steps[0].copy(
            status = TaskStepStatus.DONE,
            detailFa = "${primary.name} (${primary.phone})",
        )

        // Step 2: Check confirmed current location separately from saved locations
        steps[1] = steps[1].copy(status = TaskStepStatus.RUNNING)
        val currentLocation = prefs.confirmedCurrentLocation
        val savedLocDesc = if (task.locationReference != null) {
            locationRepository.findLocation(task.locationReference)?.fullName ?: ""
        } else {
            ""
        }
        val locDetail = when {
            currentLocation.isNotBlank() -> "موقعیت فعلی تأییدشده: $currentLocation"
            savedLocDesc.isNotBlank() -> "موقعیت فعلی تأیید نشده (مکان ذخیره‌شده: $savedLocDesc)"
            else -> "موقعیت فعلی ثبت‌نشده است"
        }
        steps[1] = steps[1].copy(status = TaskStepStatus.DONE, detailFa = locDetail)

        // Step 3: Build transparent Sol message
        steps[2] = steps[2].copy(status = TaskStepStatus.RUNNING)
        val rawBody = buildString {
            if (task.messageBody.isNotBlank()) append(task.messageBody)
            if (task.isMultiStepReport && currentLocation.isNotBlank() && !task.messageBody.contains(currentLocation)) {
                if (isNotEmpty()) append(" — ")
                append("وضعیت فعلی: در $currentLocation هستم.")
            }
        }
        val transparentSms = callScriptManager.buildTransparentMessage(
            ownerName = prefs.ownerName,
            contact = primary,
            body = rawBody,
            confirmedLocation = currentLocation,
        )
        val callPreview = callScriptManager.buildCallScriptPreview(
            ownerName = prefs.ownerName,
            contact = primary,
            message = rawBody,
        )
        steps[2] = steps[2].copy(status = TaskStepStatus.DONE, detailFa = "متن با معرفی Sol آماده شد")

        // Step 4: Request explicit user confirmation
        steps[3] = steps[3].copy(
            status = TaskStepStatus.WAITING_CONFIRMATION,
            detailFa = "در انتظار تأیید کاربر…",
        )
        if (task.isMultiStepReport) {
            _activeTask.value = _activeTask.value?.copy(steps = steps.toList())
        }

        val defaultChannel = when {
            task.preferSms -> CommunicationChannel.SMS
            prefs.deliveryPreference == DeliveryPreference.SMS_PREFERRED -> CommunicationChannel.SMS
            else -> CommunicationChannel.CALL
        }

        confirmationManager.requestConfirmation(
            PendingCommunicationConfirmation(
                channel = defaultChannel,
                candidateContacts = matches,
                selectedContact = primary,
                messageBody = transparentSms,
                callScriptPreview = callPreview,
                confirmedCurrentLocation = currentLocation,
                isMultiStepReport = task.isMultiStepReport,
            )
        )
    }

    suspend fun confirmAndExecute(
        confirmation: PendingCommunicationConfirmation,
        onCompletedReport: (String) -> Unit,
    ) {
        val launched = when (confirmation.channel) {
            CommunicationChannel.CALL -> callManager.openDialer(confirmation.selectedContact.phone)
            CommunicationChannel.SMS -> messageManager.openSmsComposer(
                confirmation.selectedContact.phone,
                confirmation.messageBody,
            )
        }

        val actionLabel = if (confirmation.channel == CommunicationChannel.CALL) {
            "شماره‌گیر تماس"
        } else {
            "صفحهٔ ارسال پیامک"
        }
        val report = if (launched) {
            "تأیید انجام شد: $actionLabel برای ${confirmation.selectedContact.name} (${confirmation.selectedContact.phone}) باز شد."
        } else {
            "برنامهٔ $actionLabel روی این دستگاه باز نشد."
        }

        _activeTask.value?.let { current ->
            val updated = current.steps.toMutableList()
            if (updated.size >= 6) {
                updated[3] = updated[3].copy(status = TaskStepStatus.DONE, detailFa = "تأیید شد")
                updated[4] = updated[4].copy(
                    status = if (launched) TaskStepStatus.DONE else TaskStepStatus.FAILED,
                    detailFa = actionLabel,
                )
                updated[5] = updated[5].copy(status = TaskStepStatus.DONE, detailFa = "در سوابق ثبت شد")
            }
            _activeTask.value = current.copy(steps = updated, isComplete = true)
        }

        historyRepository.record(
            userText = "تأیید ${confirmation.channel.labelFa} با ${confirmation.selectedContact.name}",
            solText = report,
            intentAction = confirmation.channel.name,
            status = if (launched) "CONFIRMED" else "FAILED",
        )

        confirmationManager.dismiss()
        onCompletedReport(report)
    }

    fun cancelConfirmation(onCancelledReport: (String) -> Unit) {
        val target = confirmationManager.pending.value?.selectedContact?.name ?: "مخاطب"
        confirmationManager.dismiss()
        _activeTask.value?.let { current ->
            val updated = current.steps.toMutableList()
            if (updated.size >= 4) {
                updated[3] = updated[3].copy(status = TaskStepStatus.FAILED, detailFa = "لغو توسط کاربر")
            }
            _activeTask.value = current.copy(steps = updated, isComplete = true)
        }
        onCancelledReport("عملیات ارتباطی با $target توسط شما لغو شد.")
    }

    fun dismissTaskCard() {
        _activeTask.value = null
    }

    fun requestDirectCommunication(
        contact: ResolvedContact,
        channel: CommunicationChannel,
        ownerName: String,
        confirmedLocation: String,
    ) {
        val msg = callScriptManager.buildTransparentMessage(
            ownerName = ownerName,
            contact = contact,
            body = "",
            confirmedLocation = confirmedLocation,
        )
        val preview = callScriptManager.buildCallScriptPreview(
            ownerName = ownerName,
            contact = contact,
            message = null,
        )
        confirmationManager.requestConfirmation(
            PendingCommunicationConfirmation(
                channel = channel,
                candidateContacts = listOf(contact),
                selectedContact = contact,
                messageBody = msg,
                callScriptPreview = preview,
                confirmedCurrentLocation = confirmedLocation,
                isMultiStepReport = false,
            )
        )
    }
}
