package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.*
import dev.valnook.data.portability.*
import dev.valnook.data.repository.*
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.time.Clock
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class WalletPrivateDataTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val clock=Clock.systemUTC()
    private lateinit var db:ValnookDatabase
    private lateinit var wallet:RoomWallet
    private lateinit var vault:LocalWalletPrivateRepository
    private val directoryName="wallet-private-test-${UUID.randomUUID()}"
    private val fixture=WalletPrivateContent("TEST-ONLY-1234567890123456789012345678","PRIVATE-EXP","9876","8765",0xff1234abL,0xffa1b2c3L,showNumberSpacing=false)
    @Before fun prepare(){
        db=Room.inMemoryDatabaseBuilder(context,ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
        wallet=RoomWallet(db,clock);vault=LocalWalletPrivateRepository(context,db,directoryName=directoryName)
    }
    @After fun close(){
        db.close()
        File(context.noBackupFilesDir,directoryName).deleteRecursively()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry("${context.packageName}.$directoryName")}
    }
    private suspend fun card()=wallet.save(null,null,"Private fixture",null,null)
    private fun engine()=RoomPortabilityEngine(context,db,clock,AppBuildInfo("valnook","0.0.13",13,"20261008.2","20261008.2",DATABASE_SCHEMA_VERSION))
    private fun unpack(bytes:ByteArray):Map<String,String> = ZipInputStream(ByteArrayInputStream(bytes)).use{zip->
        buildMap{while(true){val entry=zip.nextEntry?:break;put(entry.name,zip.readBytes().toString(Charsets.UTF_8))}}
    }
    @Test fun reopening_disk_database_preserves_identity_ciphertext_key_and_warning_preference()=runBlocking {
        // Isolated on-disk fixture; never reopen or clear the application's real database.
        // 独立磁盘数据库验证重启边界，绝不读取或清理用户实际数据库。
        val databaseName="private-reopen-${UUID.randomUUID()}.db"
        fun open()=Room.databaseBuilder(context,ValnookDatabase::class.java,databaseName)
            .addCallback(ValnookDatabase.seed).build()
        db.close();db=open()
        try {
            wallet=RoomWallet(db,clock);vault=LocalWalletPrivateRepository(context,db,directoryName=directoryName)
            val id=card();val saved=vault.save(id,fixture);vault.dismissWarning()
            val token=db.walletPrivateIdentity().token(id)!!
            val file=File(context.noBackupFilesDir,"$directoryName/$token.bin")
            val encrypted=file.readBytes()
            db.close();db=open()
            vault=LocalWalletPrivateRepository(context,db,directoryName=directoryName)
            vault.cleanup()
            assertEquals(token,db.walletPrivateIdentity().token(id))
            assertEquals(saved,vault.read(id));assertTrue(vault.warningDismissed())
            assertArrayEquals(encrypted,file.readBytes())
            assertEquals(saved.copy(revision=2),vault.save(id,saved))
        } finally { db.close();context.deleteDatabase(databaseName) }
    }
    @Test fun encrypted_roundtrip_fresh_nonce_and_recreated_repository_preserve_values()=runBlocking {
        val id=card();val saved=vault.save(id,fixture)
        val token=db.walletPrivateIdentity().token(id)!!
        val path=File(context.noBackupFilesDir,"$directoryName/$token.bin")
        val first=path.readBytes();assertFalse(first.toString(Charsets.UTF_8).contains(fixture.number))
        assertEquals(saved,LocalWalletPrivateRepository(context,db,directoryName=directoryName).read(id))
        val again=vault.save(id,saved);assertFalse(first.contentEquals(path.readBytes()))
        assertEquals(again,vault.read(id));assertTrue(runCatching{vault.save(id,saved)}.isFailure)
        wallet.save(id,1,"Renamed",null,null)
        assertEquals(again,vault.read(id))
        assertEquals(0,db.audit().generation().compareTo(3L)) // create + rename only; private writes do not audit.
    }
    @Test fun independent_crypto_verification_proves_aes256_gcm_nonexportable_key_and_card_binding()=runBlocking {
        val id=card();val saved=vault.save(id,fixture)
        val token=db.walletPrivateIdentity().token(id)!!
        val file=File(context.noBackupFilesDir,"$directoryName/$token.bin")
        val encrypted=file.readBytes()
        listOf(fixture.number,fixture.expiry,fixture.cvv1,fixture.cvv2).forEach { marker->
            assertFalse("Sensitive value found in stored file",encrypted.toString(Charsets.UTF_8).contains(marker))
        }
        val key=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            .getKey("${context.packageName}.$directoryName",null) as javax.crypto.SecretKey
        assertEquals("AES",key.algorithm);assertNull("Keystore key must not be exportable",key.encoded)
        val info=javax.crypto.SecretKeyFactory.getInstance("AES","AndroidKeyStore")
            .getKeySpec(key,android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
        assertEquals(256,info.keySize);assertTrue(info.blockModes.contains("GCM"))
        // Independent verifier, not the repository decoder: prove nonce/tag layout and authenticated plaintext.
        // 独立于仓库解码代码核验真实文件：只有正确 Keystore 密钥 + nonce + 卡片 AAD 才能解密。
        fun decrypt(aad:String):ByteArray=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(javax.crypto.Cipher.DECRYPT_MODE,key,javax.crypto.spec.GCMParameterSpec(128,encrypted.copyOfRange(1,13)))
            updateAAD(aad.toByteArray(Charsets.UTF_8));doFinal(encrypted,13,encrypted.size-13)
        }
        val plaintext=decrypt(token)
        try { DataInputStream(ByteArrayInputStream(plaintext)).use { input->
            assertEquals(2,input.readInt());assertEquals(fixture.number,input.readUTF());assertEquals(fixture.expiry,input.readUTF())
            assertEquals(fixture.cvv1,input.readUTF());assertEquals(fixture.cvv2,input.readUTF())
            assertEquals(fixture.backColor,input.readLong());assertEquals(fixture.edgeColor,input.readLong())
            assertEquals(1L,input.readLong());assertFalse(input.readBoolean());assertEquals(0,input.available())
        } } finally { plaintext.fill(0) }
        val wrong=runCatching{decrypt(UUID.randomUUID().toString())}.exceptionOrNull()
        assertTrue("Wrong card must fail authentication",wrong is javax.crypto.AEADBadTagException)
        vault.save(id,saved)
        assertFalse("Each save needs a fresh GCM IV",encrypted.copyOfRange(1,13).contentEquals(file.readBytes().copyOfRange(1,13)))
        val second=card();vault.save(second,fixture)
        val secondToken=db.walletPrivateIdentity().token(second)!!
        File(context.noBackupFilesDir,"$directoryName/$secondToken.bin").writeBytes(encrypted)
        assertTrue("Swapped card files must fail authentication",runCatching{vault.read(second)}.isFailure)
    }
    @Test fun corrupted_ciphertext_and_missing_key_fail_closed_without_plaintext_fallback()=runBlocking {
        val id=card();vault.save(id,fixture)
        val token=db.walletPrivateIdentity().token(id)!!;val file=File(context.noBackupFilesDir,"$directoryName/$token.bin")
        val original=file.readBytes();file.writeBytes(original.copyOf().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()})
        assertTrue(runCatching{vault.read(id)}.isFailure)
        file.writeBytes(original)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry("${context.packageName}.$directoryName")}
        assertTrue(runCatching{vault.read(id)}.isFailure)
        assertTrue(runCatching{vault.save(id,fixture)}.isFailure)
        assertArrayEquals(original,file.readBytes())
    }
    @Test fun legacy_encrypted_records_default_to_spacing_and_require_valid_cvv_on_next_save()=runBlocking {
        val id=card();val saved=vault.save(id,fixture)
        val token=db.walletPrivateIdentity().token(id)!!
        val file=File(context.noBackupFilesDir,"$directoryName/$token.bin")
        val key=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            .getKey("${context.packageName}.$directoryName",null) as javax.crypto.SecretKey
        val bytes=ByteArrayOutputStream().apply { DataOutputStream(this).use { out->
            out.writeInt(1);out.writeUTF(saved.number);out.writeUTF(saved.expiry)
            out.writeUTF("OLD-CVV");out.writeUTF("")
            out.writeLong(saved.backColor);out.writeLong(saved.edgeColor);out.writeLong(saved.revision)
        } }.toByteArray()
        try {
            val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(javax.crypto.Cipher.ENCRYPT_MODE,key);updateAAD(token.toByteArray(Charsets.UTF_8))
            }
            file.writeBytes(byteArrayOf(1)+cipher.iv+cipher.doFinal(bytes))
        } finally { bytes.fill(0) }
        val legacy=vault.read(id)
        assertTrue(legacy.showNumberSpacing);assertEquals("OLD-CVV",legacy.cvv1)
        assertTrue(runCatching{vault.save(id,legacy)}.isFailure)
        val updated=vault.save(id,legacy.copy(cvv1="0004",showNumberSpacing=false))
        assertEquals(updated,LocalWalletPrivateRepository(context,db,directoryName=directoryName).read(id))
        assertFalse(updated.showNumberSpacing);assertEquals("0004",updated.cvv1)
    }
    @Test fun backup_and_workbook_exclude_values_colors_identity_and_ciphertext()=runBlocking {
        val id=card();vault.save(id,fixture);vault.dismissWarning()
        val token=db.walletPrivateIdentity().token(id)!!
        val out=ByteArrayOutputStream();engine().createBackup(UUID.randomUUID().toString(),out){}
        val workbook=ByteArrayOutputStream();engine().exportWorkbook(UUID.randomUUID().toString(),AppLanguage.ENGLISH,false,workbook){}
        listOf(unpack(out.toByteArray()),unpack(workbook.toByteArray())).forEach { entries ->
            val all=entries.entries.joinToString("\n"){"${it.key}\n${it.value}"}
            listOf(fixture.number,fixture.expiry,fixture.cvv1,fixture.cvv2,fixture.backColor.toString(),fixture.edgeColor.toString(),token,
                "local_wallet_identity","wallet-private","backColor","edgeColor","showNumberSpacing").forEach { assertFalse("Export contains private marker",all.contains(it)) }
        }
        assertFalse(BackupContract.tables.any{it.table=="local_wallet_identity"})
        assertEquals(fixture.copy(revision=1),vault.read(id))
    }
    @Test fun successful_restore_revokes_old_identity_even_when_card_id_is_reused()=runBlocking {
        val id=card();vault.save(id,fixture)
        val engine=engine();val out=ByteArrayOutputStream();engine.createBackup(UUID.randomUUID().toString(),out){}
        val oldToken=db.walletPrivateIdentity().token(id)!!
        val staged=engine.prepareRestore(ByteArrayInputStream(out.toByteArray()),"private-test.val_backup"){}
        try{engine.commitRestore(staged){}}finally{engine.close(staged)}
        assertEquals(id,wallet.observeCards().first().single().id)
        assertNull(db.walletPrivateIdentity().token(id));assertEquals(WalletPrivateContent(),vault.read(id))
        vault.cleanup();assertFalse(File(context.noBackupFilesDir,"$directoryName/$oldToken.bin").exists())
        vault.save(id,fixture);assertNotEquals(oldToken,db.walletPrivateIdentity().token(id))
    }
    @Test fun corrupt_archive_and_injected_restore_rollback_preserve_original_private_data()=runBlocking {
        val id=card();val saved=vault.save(id,fixture);val token=db.walletPrivateIdentity().token(id)
        val engine=engine();val out=ByteArrayOutputStream();engine.createBackup(UUID.randomUUID().toString(),out){}
        assertTrue(runCatching{engine.prepareRestore(ByteArrayInputStream(out.toByteArray().copyOf(40)),"broken") {}}.isFailure)
        val staged=engine.prepareRestore(ByteArrayInputStream(out.toByteArray()),"private-test.val_backup"){}
        try {
            val importer=RestoreImporter(db,clock){point->if(point==RestorePoint.AFTER_CANDIDATE_COPY)error("Injected rollback")}
            assertTrue(runCatching{importer.commit(staged,staged.generationAtPreview)}.isFailure)
        }finally{engine.close(staged)}
        assertEquals(token,db.walletPrivateIdentity().token(id));assertEquals(saved,vault.read(id))
    }
    @Test fun deletion_and_clear_revoke_links_and_cleanup_orphans_and_preferences()=runBlocking {
        val id=card();val second=card();vault.save(id,fixture);val saved=vault.save(second,fixture);vault.dismissWarning()
        wallet.delete(id,1);assertNull(db.walletPrivateIdentity().token(id));assertTrue(runCatching{vault.read(id)}.isFailure)
        vault.cleanup();assertEquals(saved,vault.read(second));assertTrue(vault.warningDismissed())
        RoomDataMaintenance(db).clearBusinessData();vault.cleanup(clearPreferences=true)
        assertTrue(db.walletPrivateIdentity().tokens().isEmpty());assertFalse(vault.warningDismissed())
        assertTrue(File(context.noBackupFilesDir,directoryName).listFiles().orEmpty().isEmpty())
        assertFalse(java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.containsAlias("${context.packageName}.$directoryName"))
    }
    @Test fun maintenance_blocks_reads_and_writes_and_warning_is_device_local()=runBlocking {
        val id=card();vault.save(id,fixture);vault.dismissWarning()
        assertTrue(LocalWalletPrivateRepository(context,db,directoryName=directoryName).warningDismissed())
        db.audit().setMaintenance(true,clock.millis())
        assertTrue(runCatching{vault.read(id)}.isFailure);assertTrue(runCatching{vault.save(id,fixture)}.isFailure)
        db.audit().setMaintenance(false,clock.millis())
        assertEquals(fixture.copy(revision=1),vault.read(id))
    }
    @Test fun account_deletion_and_web_projections_never_expose_or_remove_private_content()=runBlocking {
        val commands=dev.valnook.data.transaction.RoomFinancialCommands(db,clock)
        val account=commands.testAccount("Linked account")
        commands.execute(SetCashBalance(testOperationId(),account,"USD",100,null))
        val cash=RoomOverview(db).snapshot().cash.single()
        val id=wallet.save(null,null,"Linked card",cash.id,null);val saved=vault.save(id,fixture)
        val reads=dev.valnook.data.webadmin.RoomWebAdminReadRepository(db,RoomOverview(db),RoomStatistics(db,clock),RoomDeposits(db.deposits()),RoomInvestments(db))
        val snapshot=reads.snapshot().toString()
        assertFalse(snapshot.contains(fixture.number));assertFalse(snapshot.contains(fixture.cvv1))
        assertNull(reads.accountIconImage(db.walletPrivateIdentity().token(id)!!))
        val preview=commands.previewAccountDeletion(account,cash.id)
        commands.execute(DeleteBalanceAccount(testOperationId(),account,cash.id,cash.revision,DeletionConfirmation(preview.ticket,preview.code)))
        assertEquals(saved,vault.read(id));assertNull(wallet.observeCards().first().single().boundCashAccountId)
    }
}
