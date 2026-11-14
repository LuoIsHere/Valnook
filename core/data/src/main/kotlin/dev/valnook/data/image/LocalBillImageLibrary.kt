package dev.valnook.data.image

import dev.valnook.domain.model.BillImage
import dev.valnook.domain.repository.BillImageLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [BillImageStore] for the bill pages; a dropped picture is deleted in [scope], so a closing page need not wait. */
class LocalBillImageLibrary(private val store: BillImageStore, private val scope: CoroutineScope) : BillImageLibrary {
    override suspend fun store(bytes: ByteArray): BillImage = store.put(bytes)
    override fun hold(keys: Collection<String>) = store.pin(keys)
    override fun saved(keys: Collection<String>) = store.unpin(keys)
    override fun drop(keys: Collection<String>) {
        if (keys.isEmpty()) return
        scope.launch { runCatching { store.discard(keys) } }
    }
    override suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!store.has(key)) null else runCatching { store.file(key).readBytes() }.getOrNull()
    }
}
