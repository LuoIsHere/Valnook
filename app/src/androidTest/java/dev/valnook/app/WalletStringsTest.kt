package dev.valnook.app

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import dev.valnook.feature.wallet.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wallet's text moved from inline English and Chinese pairs to string resources: each resource reads back, in each
 * language, exactly as the pair it replaced (a placeholder stands for each value filled in).
 */
class WalletStringsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun resources(tag: String) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }).resources

    private val expected = listOf(
        Triple(R.string.wallet_back_wallet, "Back to wallet", "返回卡包") to arrayOf<Any>(),
        Triple(R.string.wallet_linked_account_was_deleted, "The linked account was deleted.", "原绑定子账户已删除。") to arrayOf<Any>(),
        Triple(R.string.wallet_no_linked_account, "No linked account", "尚未绑定子账户") to arrayOf<Any>(),
        Triple(R.string.wallet_keep_card_as_collection_link, "Keep this card as a collection, or link an account to see its balance and activity.", "可以仅收藏卡面，也可以绑定子账户查看余额和资金变动。") to arrayOf<Any>(),
        Triple(R.string.wallet_link_account, "Link account", "绑定子账户") to arrayOf<Any>(),
        Triple(R.string.wallet_funds_activity, "Funds activity", "资金变动") to arrayOf<Any>(),
        Triple(R.string.wallet_previous_month, "Previous month", "上个月") to arrayOf<Any>(),
        Triple(R.string.wallet_next_month, "Next month", "下个月") to arrayOf<Any>(),
        Triple(R.string.wallet_could_not_load_month_retry, "Could not load this month. Retry", "本月记录加载失败，点击重试") to arrayOf<Any>(),
        Triple(R.string.wallet_retry_loading_more, "Retry loading more", "继续加载失败，点击重试") to arrayOf<Any>(),
        Triple(R.string.wallet_choose_month, "Choose month", "选择月份") to arrayOf<Any>(),
        Triple(R.string.wallet_year, "Year", "年份") to arrayOf<Any>(),
        Triple(R.string.wallet_cancel, "Cancel", "取消") to arrayOf<Any>(),
        Triple(R.string.wallet_add_card, "Add card", "添加卡片") to arrayOf<Any>(),
        Triple(R.string.wallet_edit_card, "Edit card", "编辑卡片") to arrayOf<Any>(),
        Triple(R.string.wallet_card_name, "Card name", "卡片名称") to arrayOf<Any>(),
        Triple(R.string.wallet_1_40_characters_only_shown, "1–40 characters · only shown outside the card", "1–40 个字符，仅在卡面外显示") to arrayOf<Any>(),
        Triple(R.string.wallet_choose_image, "Choose image", "选择图片") to arrayOf<Any>(),
        Triple(R.string.wallet_from_card_faces, "From card faces", "从卡面库选择") to arrayOf<Any>(),
        Triple(R.string.wallet_remove_image, "Remove image", "移除图片") to arrayOf<Any>(),
        Triple(R.string.wallet_use_blank_redacted_card_face, "Use a blank or redacted card face. Images stay on this device and are included in your backups. Applied images are kept in Settings › Card faces.", "建议使用空白或已遮挡敏感信息的卡面。图片在本机处理，并随完整备份保存；应用过的图片会保留在“设置 › 卡面库”。") to arrayOf<Any>(),
        Triple(R.string.wallet_link_subaccount_optional, "Link a subaccount (optional)", "绑定子账户（可选）") to arrayOf<Any>(),
        Triple(R.string.wallet_these_cards_show_all_records, "These cards show all records from the same subaccount.", "这些卡片将展示同一子账户的全部记录。") to arrayOf<Any>(),
        Triple(R.string.wallet_could_not_save_your_draft, "Could not save. Your draft is kept; retry or reopen if the card changed elsewhere.", "保存失败，已保留填写内容。请重试；若卡片已被其他操作修改，请重新编辑。") to arrayOf<Any>(),
        Triple(R.string.wallet_saving, "Saving…", "保存中…") to arrayOf<Any>(),
        Triple(R.string.wallet_save, "Save", "保存") to arrayOf<Any>(),
        Triple(R.string.wallet_link_subaccount, "Link a subaccount", "绑定子账户") to arrayOf<Any>(),
        Triple(R.string.wallet_no_linked_account_2, "No linked account", "不绑定") to arrayOf<Any>(),
        Triple(R.string.wallet_credit, "Credit", "信用") to arrayOf<Any>(),
        Triple(R.string.wallet_savings, "Savings", "储蓄") to arrayOf<Any>(),
        Triple(R.string.wallet_adjust_card_face, "Adjust card face", "调整卡面") to arrayOf<Any>(),
        Triple(R.string.wallet_move_pinch_crop_fixed_card, "Move and pinch to crop · fixed card proportions", "移动、双指缩放裁剪 · 固定卡片比例") to arrayOf<Any>(),
        Triple(R.string.wallet_rotate_90, "Rotate 90°", "旋转 90°") to arrayOf<Any>(),
        Triple(R.string.wallet_unable_process_image_use_static, "Unable to process image. Use a static JPEG, PNG or WebP under 20 MiB and 40 MP.", "无法处理图片，请选择 20 MiB、4000 万像素以内的静态 JPEG、PNG 或 WebP。") to arrayOf<Any>(),
        Triple(R.string.wallet_use_image, "Use image", "使用图片") to arrayOf<Any>(),
        Triple(R.string.wallet_upload_card_face, "Upload card face", "上传卡面") to arrayOf<Any>(),
        Triple(R.string.wallet_use, "In use", "使用中") to arrayOf<Any>(),
        Triple(R.string.wallet_not_use, "Not in use", "未使用") to arrayOf<Any>(),
        Triple(R.string.wallet_unable_load_card_faces_reopen, "Unable to load card faces. Reopen this page to retry.", "卡面加载失败，请重新打开此页面重试。") to arrayOf<Any>(),
        Triple(R.string.wallet_card_faces_stay_here_after, "Card faces stay here after a card changes face or is deleted, until you delete them. A face in use cannot be deleted.", "卡片换掉卡面或被删除后，卡面仍保留在这里，直到你删除它。正在使用的卡面不能删除。") to arrayOf<Any>(),
        Triple(R.string.wallet_no_card_faces_yet_tap, "No card faces yet. Tap ＋ to upload one, or choose an image when editing a card.", "还没有卡面。点按 ＋ 上传，或在编辑卡片时选择图片。") to arrayOf<Any>(),
        Triple(R.string.wallet_delete_card_face, "Delete this card face?", "删除这张卡面？") to arrayOf<Any>(),
        Triple(R.string.wallet_removed_from_library_from_later, "It is removed from the library and from later backups. Existing backups are unchanged.", "卡面将从卡面库删除，之后的备份不再包含它；已有备份不受影响。") to arrayOf<Any>(),
        Triple(R.string.wallet_delete, "Delete", "删除") to arrayOf<Any>(),
        Triple(R.string.wallet_used_by, "Used by ", "用于：") to arrayOf<Any>(),
        Triple(R.string.wallet_name_separator, ", ", "、") to arrayOf<Any>(),
        Triple(R.string.wallet_added, "Added ", "添加于 ") to arrayOf<Any>(),
        Triple(R.string.wallet_save_image, "Save image", "保存图片") to arrayOf<Any>(),
        Triple(R.string.wallet_delete_card_face_2, "Delete card face", "删除卡面") to arrayOf<Any>(),
        Triple(R.string.wallet_add_card_faces, "Add to card faces", "添加到卡面库") to arrayOf<Any>(),
        Triple(R.string.wallet_card_face_name, "Card face name", "卡面名称") to arrayOf<Any>(),
        Triple(R.string.wallet_1_40_characters_only_shown_2, "1–40 characters · only shown in the library", "1–40 个字符，仅在卡面库中显示") to arrayOf<Any>(),
        Triple(R.string.wallet_card_faces, "Card faces", "卡面库") to arrayOf<Any>(),
        Triple(R.string.wallet_no_card_faces_yet_faces, "No card faces yet. Faces you apply or upload in Settings › Card faces appear here.", "还没有卡面。应用过的卡面和在“设置 › 卡面库”上传的卡面会显示在这里。") to arrayOf<Any>(),
        Triple(R.string.wallet_card_face_already_library, "This card face is already in the library.", "这张卡面已在卡面库中。") to arrayOf<Any>(),
        Triple(R.string.wallet_library_full_faces_delete_faces, "The library is full (<1> faces). Delete faces you no longer need in Settings › Card faces.", "卡面库已满（<1> 张），请先在“设置 › 卡面库”中删除不用的卡面。") to arrayOf<Any>("<1>"),
        Triple(R.string.wallet_card_face_use_change_face, "This card face is in use. Change the face of the cards using it first.", "这张卡面正在使用，请先为使用它的卡片更换卡面。") to arrayOf<Any>(),
        Triple(R.string.wallet_unable_process_image_choose_another, "Unable to process image. Choose another image.", "无法处理图片，请换一张图片。") to arrayOf<Any>(),
        Triple(R.string.wallet_changes_could_not_saved_your, "Changes could not be saved. Your data is unchanged; please retry.", "保存失败，已有数据未改变，请重试。") to arrayOf<Any>(),
        Triple(R.string.wallet_added_card_faces, "Added to card faces.", "已添加到卡面库。") to arrayOf<Any>(),
        Triple(R.string.wallet_card_face_deleted, "Card face deleted.", "卡面已删除。") to arrayOf<Any>(),
        Triple(R.string.wallet_image_saved, "Image saved.", "图片已保存。") to arrayOf<Any>(),
        Triple(R.string.wallet_unable_save_image_please_retry, "Unable to save the image. Please retry.", "图片保存失败，请重试。") to arrayOf<Any>(),
        Triple(R.string.wallet_untitled_card_face, "Untitled card face", "未命名卡面") to arrayOf<Any>(),
        Triple(R.string.wallet_built_artwork, "Built-in artwork", "内置卡面") to arrayOf<Any>(),
        Triple(R.string.wallet_default, "Default", "默认") to arrayOf<Any>(),
        Triple(R.string.wallet_solid, "Solid", "纯色") to arrayOf<Any>(),
        Triple(R.string.wallet_gradient, "Gradient", "渐变") to arrayOf<Any>(),
        Triple(R.string.wallet_glow, "Glow", "柔光") to arrayOf<Any>(),
        Triple(R.string.wallet_plain, "Plain", "无图形") to arrayOf<Any>(),
        Triple(R.string.wallet_rings, "Rings", "圆环") to arrayOf<Any>(),
        Triple(R.string.wallet_arc, "Arc", "圆弧") to arrayOf<Any>(),
        Triple(R.string.wallet_stripes, "Stripes", "斜纹") to arrayOf<Any>(),
        Triple(R.string.wallet_circles, "Circles", "圆形") to arrayOf<Any>(),
        Triple(R.string.wallet_blocks, "Blocks", "方块") to arrayOf<Any>(),
        Triple(R.string.wallet_base, "Base", "底色") to arrayOf<Any>(),
        Triple(R.string.wallet_gradient_end, "Gradient end", "渐变尾色") to arrayOf<Any>(),
        Triple(R.string.wallet_pattern, "Pattern", "图形颜色") to arrayOf<Any>(),
        Triple(R.string.wallet_show_card_back, "Show card back", "翻到卡片背面") to arrayOf<Any>(),
        Triple(R.string.wallet_show_card_front, "Show card front", "翻到卡片正面") to arrayOf<Any>(),
        Triple(R.string.wallet_copy_card_number, "Copy card number", "复制卡号") to arrayOf<Any>(),
        Triple(R.string.wallet_edit_private_card_details, "Edit private card details", "编辑卡片背面") to arrayOf<Any>(),
        Triple(R.string.wallet_copy_expiry_date, "Copy expiry date", "复制有效期") to arrayOf<Any>(),
        Triple(R.string.wallet_copy_cardholder_name, "Copy cardholder name", "复制持卡人") to arrayOf<Any>(),
        Triple(R.string.wallet_copy, "Copy <1>", "复制 <1>") to arrayOf<Any>("<1>"),
        Triple(R.string.wallet_private_card_details, "Private card details", "卡片背面隐私提示") to arrayOf<Any>(),
        Triple(R.string.wallet_card_numbers_cardholder_names_expiry, "Card numbers, cardholder names, expiry dates, CVV1 and CVV2 are sensitive. Disclosure may cause financial loss.", "卡号、持卡人姓名、有效期、CVV1 和 CVV2 属于敏感信息，泄露可能造成财产损失。") to arrayOf<Any>(),
        Triple(R.string.wallet_details_entered_here_are_encrypted, "Details entered here are encrypted on this device only, with keys managed by Android Keystore and content encrypted with AES-GCM. They are never uploaded, never included in local or OneDrive backups or file exports, and cannot be accessed from Web administration.", "您填写的背面内容仅加密保存在本设备，使用 Android Keystore 管理密钥，并通过 AES-GCM 加密内容。不会上传至服务器，不会纳入本地备份、OneDrive 等云备份或其他文件导出，网页端管理也无法访问。") to arrayOf<Any>(),
        Triple(R.string.wallet_uninstalling_clearing_app_data_successfully, "Uninstalling, clearing app data or successfully replacing data from a backup makes these details unrecoverable. Enable your device's app lock for Valnook where available.", "卸载应用、清除应用数据或成功覆盖恢复备份后，这部分内容将无法恢复。建议在设备支持时，为 Valnook 启用系统应用锁。") to arrayOf<Any>(),
        Triple(R.string.wallet_copying_field_places_system_clipboard, "Copying a field places it on the system clipboard, outside this app's encrypted storage. Use this action with care.", "主动复制会将对应内容写入系统剪贴板，不受本地加密存储保护，请谨慎使用。") to arrayOf<Any>(),
        Triple(R.string.wallet_fullest_extent_permitted_by_applicable, "To the fullest extent permitted by applicable law, the developer is not liable for financial loss arising from disclosure of card details.", "在适用法律允许的最大范围内，开发者不对卡片信息泄露造成的财产损失承担责任。") to arrayOf<Any>(),
        Triple(R.string.wallet_don_t_show_again, "Don't show again", "不再显示") to arrayOf<Any>(),
        Triple(R.string.wallet_ok, "OK", "确定") to arrayOf<Any>(),
        Triple(R.string.wallet_discard_unsaved_changes, "Discard unsaved changes?", "放弃未保存的修改？") to arrayOf<Any>(),
        Triple(R.string.wallet_keep_editing, "Keep editing", "继续编辑") to arrayOf<Any>(),
        Triple(R.string.wallet_discard, "Discard", "放弃") to arrayOf<Any>(),
        Triple(R.string.wallet_private_details_unavailable, "Private details unavailable", "私密内容暂不可用") to arrayOf<Any>(),
        Triple(R.string.wallet_could_not_securely_read_save, "Could not securely read or save these details. Retry after closing the editor. If the local key or encrypted file is lost, backups cannot recover it.", "无法安全读取或保存这些内容，请关闭编辑后重试。如果本地密钥或加密文件丢失，备份无法恢复这部分内容。") to arrayOf<Any>(),
        Triple(R.string.wallet_ok_2, "OK", "知道了") to arrayOf<Any>(),
        Triple(R.string.wallet_card_back_colors, "Card back colors", "卡片背面配色") to arrayOf<Any>(),
        Triple(R.string.wallet_private_card_details_2, "Private card details", "编辑卡片背面") to arrayOf<Any>(),
        Triple(R.string.wallet_card_number, "Card number", "卡号") to arrayOf<Any>(),
        Triple(R.string.wallet_up_38_characters, "Up to 38 characters", "最多填写 38 个字符") to arrayOf<Any>(),
        Triple(R.string.wallet_up_38_characters_symbols_allowed, "Up to 38 characters · symbols allowed", "最多 38 个字符，可使用 *、- 等符号") to arrayOf<Any>(),
        Triple(R.string.wallet_show_card_number_spacing, "Show card number spacing", "显示卡号间隔") to arrayOf<Any>(),
        Triple(R.string.wallet_expiry_mm_yy, "Expiry · MM/YY", "有效期 · MM/YY") to arrayOf<Any>(),
        Triple(R.string.wallet_cardholder, "Cardholder", "持卡人") to arrayOf<Any>(),
        Triple(R.string.wallet_encrypted_device_only_excluded_from, "Encrypted on this device only · excluded from backups and file exports", "仅本机加密保存 · 不纳入备份或文件导出") to arrayOf<Any>(),
        Triple(R.string.wallet_up_4_digits_optional, "Up to 4 digits · optional", "最多 4 位数字，可留空") to arrayOf<Any>(),
        Triple(R.string.wallet_up_characters_optional, "Up to <1> characters · optional", "最多 <1> 个字符，可留空") to arrayOf<Any>("<1>"),
        Triple(R.string.wallet_back, "Back", "背面") to arrayOf<Any>(),
        Triple(R.string.wallet_edge, "Edge", "侧面") to arrayOf<Any>(),
        Triple(R.string.wallet_select_color, "Select color", "选择颜色") to arrayOf<Any>(),
        Triple(R.string.wallet_custom_color_hex, "Custom color · HEX", "自定义颜色 · HEX") to arrayOf<Any>(),
        Triple(R.string.wallet_six_hexadecimal_digits, "Six hexadecimal digits", "填写六位十六进制颜色值") to arrayOf<Any>(),
        Triple(R.string.wallet_wallet, "Wallet", "卡包") to arrayOf<Any>(),
        Triple(R.string.wallet_unable_load_cards_reopen_wallet, "Unable to load cards. Reopen Wallet to retry.", "卡片加载失败，请重新打开卡包重试。") to arrayOf<Any>(),
        Triple(R.string.wallet_copied, "Copied", "复制成功") to arrayOf<Any>(),
        Triple(R.string.wallet_copy_failed_try_again, "Copy failed. Try again.", "复制失败，请重试") to arrayOf<Any>(),
        Triple(R.string.wallet_delete_card_prompt, "Delete this card?", "删除这张卡片？") to arrayOf<Any>(),
        Triple(R.string.wallet_card_any_private_back_details, "The card and any private back details are deleted. Its face stays in Settings › Card faces, and the linked account and its records are kept.", "只删除卡片及其本机私密背面内容；卡面保留在“设置 › 卡面库”，不删除绑定账户及其记录。") to arrayOf<Any>(),
        Triple(R.string.wallet_place_your_cards, "A place for your cards", "为喜欢的卡片留个位置") to arrayOf<Any>(),
        Triple(R.string.wallet_collect_card_face_link_existing, "Collect a card face, or link an existing account.", "收藏卡面，或关联已有子账户。") to arrayOf<Any>(),
        Triple(R.string.wallet_move_up, "Move up", "上移") to arrayOf<Any>(),
        Triple(R.string.wallet_move_down, "Move down", "下移") to arrayOf<Any>(),
    )

    @Test fun every_wallet_text_reads_as_before_in_english_and_chinese() {
        val english = resources("en-US")
        val chinese = resources("zh-Hans-CN")
        expected.forEach { (texts, args) ->
            val (id, en, zh) = texts
            assertEquals(en, if (args.isEmpty()) english.getString(id) else english.getString(id, *args))
            assertEquals(zh, if (args.isEmpty()) chinese.getString(id) else chinese.getString(id, *args))
        }
        assertEquals(125, expected.size)
    }
}
