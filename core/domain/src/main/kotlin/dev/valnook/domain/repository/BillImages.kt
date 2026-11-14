package dev.valnook.domain.repository

import dev.valnook.domain.model.BillImage

/**
 * The bill pictures on this device, for the bill pages. A picture goes on a bill through SaveBill; until then the
 * form holds it here, so it is not deleted as a picture no bill holds.
 */
interface BillImageLibrary {
    /** Stores a picture a form picked (already a compressed WebP) and holds it for that form. */
    suspend fun store(bytes: ByteArray): BillImage
    /** A form holds [keys] again, after the app was ended and the form put back. */
    fun hold(keys: Collection<String>)
    /** The form saved [keys]: bills hold them now. */
    fun saved(keys: Collection<String>)
    /** The form dropped [keys] unsaved; those no bill holds are deleted. */
    fun drop(keys: Collection<String>)
    /** The picture's bytes, or null when it is not on this device. */
    suspend fun read(key: String): ByteArray?
}
