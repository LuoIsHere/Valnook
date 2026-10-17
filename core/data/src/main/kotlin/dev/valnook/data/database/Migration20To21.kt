package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Turns stored card faces into the face library. Faces used to be deleted once no card used them, so every
 * existing face belongs to a card; it takes that card's name and save time, and the wallet looks unchanged.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE wallet_card_images ADD COLUMN label TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE wallet_card_images ADD COLUMN added_at_ms INTEGER NOT NULL DEFAULT 0")
        backfillWalletFaceLibrary(db)
    }
}

/**
 * Shared by this migration and restores of archives written before the library existed. Only fills faces that
 * have no name or time yet: the name of the first card using the face, and the earliest save time among those cards.
 */
internal fun backfillWalletFaceLibrary(db: SupportSQLiteDatabase) {
    db.execSQL("""UPDATE wallet_card_images SET label=COALESCE((SELECT c.name FROM wallet_cards c
        WHERE c.image_key=wallet_card_images.id ORDER BY c.display_order,c.id LIMIT 1),'') WHERE label=''""")
    db.execSQL("""UPDATE wallet_card_images SET added_at_ms=COALESCE((SELECT MIN(c.updated_at_ms) FROM wallet_cards c
        WHERE c.image_key=wallet_card_images.id),0) WHERE added_at_ms=0""")
}
