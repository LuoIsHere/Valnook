package dev.valnook.feature.wallet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import dev.valnook.domain.repository.WalletPrivateContent
import dev.valnook.domain.repository.WalletPrivateRepository
import kotlinx.coroutines.*

/** Ephemeral only: never put these values into saved state, logging or the overview cache. */
@Stable
internal class WalletPrivateState(private val cardId: Long?, private val repository: WalletPrivateRepository?,
    private val scope: CoroutineScope) {
    val angle = Animatable(0f)
    var content by mutableStateOf<WalletPrivateContent?>(null); private set
    var draft by mutableStateOf<WalletPrivateContent?>(null)
    var warning by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var error by mutableStateOf(false)
    var discard by mutableStateOf(false)
    var desiredBack by mutableStateOf(false); private set
    var copyNotice by mutableStateOf<Boolean?>(null); private set
    var lastCopySucceeded by mutableStateOf(true); private set
    private var copyNoticeJob: Job? = null
    // Status only, never retain clipboard values in feedback or saved state.
    // 提示仅保存成功/失败状态，不得包含复制内容，也不得写入页面恢复状态或日志。
    fun reportCopy(success: Boolean) {
        clearCopyNotice(); lastCopySucceeded = success; copyNotice = success
        copyNoticeJob = scope.launch { delay(2200); copyNotice = null }
    }
    private fun clearCopyNotice() { copyNoticeJob?.cancel(); copyNotice = null }
    var expand: suspend () -> Unit = {}
    private var job: Job? = null
    private var ticket = 0
    val available get() = cardId != null && repository != null
    val sensitive get() = content != null || draft != null || angle.value > 0f
    val editing get() = draft != null || warning

    fun flip() {
        if (!available || saving || editing) return
        clearCopyNotice()
        desiredBack = !desiredBack
        val generation = ++ticket
        job?.cancel()
        job = scope.launch {
            try {
                if (desiredBack && content == null) {
                    loading = true
                    val value = repository!!.read(cardId!!)
                    if (generation != ticket) return@launch
                    content = value
                }
                loading = false
                expand()
                angle.animateTo(if (desiredBack) 180f else 0f, tween(560, easing = WalletSelectionEasing))
                if (!desiredBack) content = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == ticket) { error = true; desiredBack = false } }
            finally { if (generation == ticket) loading = false }
        }
    }

    suspend fun front() {
        clearCopyNotice()
        ++ticket; job?.cancelAndJoin(); loading = false; desiredBack = false
        if(angle.value>0f) angle.animateTo(0f, tween(420, easing = WalletSelectionEasing))
        content = null
    }

    fun edit() {
        if (content == null || saving || loading || angle.isRunning) return
        clearCopyNotice()
        val generation = ticket
        job = scope.launch {
            try {
                val dismissed = repository!!.warningDismissed()
                if (generation == ticket) { if (dismissed) draft = content else warning = true }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == ticket) error = true }
        }
    }
    fun confirmWarning(dismissForever: Boolean) {
        val generation = ticket
        job = scope.launch {
            try {
                if (dismissForever) repository!!.dismissWarning()
                if (generation == ticket) { warning = false; draft = content }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == ticket) error = true }
        }
    }
    fun cancelWarning() {
        // Invalidate even non-cancellable storage completions: a dismissed notice must not reopen private UI.
        // 取消提示也必须撤销异步结果，避免已开始的本地写入完成后重新打开私密编辑界面。
        ++ticket; job?.cancel(); warning = false
    }
    fun cancelEdit() { if (!saving) { if (draft != content) discard = true else draft = null } }
    fun updateDraft(value: WalletPrivateContent) {
        // A delayed IME/palette callback must not recreate a closed or already-saving private draft.
        // 输入法或配色控件的迟到回调不得重新创建已关闭/正在保存的私密草稿。
        if (!saving && draft != null) draft = value
    }
    fun discardEdit() { draft = null; discard = false }
    fun save() {
        val value = draft ?: return
        if (saving) return
        val generation = ticket
        saving = true
        job = scope.launch {
            try {
                val saved = repository!!.save(cardId!!, value)
                if (generation == ticket) { content = saved; draft = null; error = false }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == ticket) error = true }
            finally { if (generation == ticket) saving = false }
        }
    }
    fun conceal() {
        clearCopyNotice()
        ++ticket; job?.cancel(); content = null; draft = null; warning = false; discard = false
        loading = false; saving = false; error = false; desiredBack = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) { angle.snapTo(0f) }
    }
}
