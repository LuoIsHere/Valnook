package dev.valnook.feature.wallet

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.valnook.designsystem.EditIcon
import dev.valnook.domain.model.WalletCard
import dev.valnook.domain.model.WalletRules
import dev.valnook.domain.repository.WalletPrivateContent
import dev.valnook.domain.repository.walletNumberLines
import kotlin.math.abs
import kotlin.math.sin
import dev.valnook.designsystem.FaIcon
import dev.valnook.designsystem.FaIcons

@Composable fun walletAddLabel()=stringResource(R.string.wallet_add_card)

@Composable fun walletFlipLabel(toBack:Boolean)=if(toBack)stringResource(R.string.wallet_show_card_back)else stringResource(R.string.wallet_show_card_front)

@Composable fun WalletFlipIcon(modifier: Modifier = Modifier) {
    FaIcon(FaIcons.rotate, null, modifier.size(20.dp))
}

@Composable internal fun WalletPaletteIcon(modifier: Modifier = Modifier) {
    FaIcon(FaIcons.palette, null, modifier.size(20.dp))
}

@Composable internal fun WalletFlippingCard(card: WalletCard, cache: WalletImageCache,
    state: WalletPrivateState, modifier: Modifier, enabled: Boolean, glow: () -> Float, shadow: () -> Float) {
    val shape = RoundedCornerShape(18.dp)
    val density = LocalDensity.current.density
    val back = state.content
    val angle = state.angle.value
    val context=LocalContext.current
    val writeClip=LocalWalletClipboard.current
    // This outer node retains exactly the same shared-element bounds on both faces.
    Box(modifier.widthIn(max=440.dp).fillMaxWidth().aspectRatio(WalletRules.ASPECT)) {
        val edge = Color((back?.edgeColor ?: 0xff858b94L).toInt())
        Canvas(Modifier.matchParentSize()) {
            val thickness=abs(sin(Math.toRadians(angle.toDouble()))).toFloat()*2.dp.toPx()
            if(thickness>.1f) drawRoundRect(edge,Offset(size.width/2-thickness/2,1.dp.toPx()),
                androidx.compose.ui.geometry.Size(thickness,size.height-2.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(thickness/2))
        }
        Box(Modifier.fillMaxSize().graphicsLayer {
            rotationY=angle
            cameraDistance=14f*density
        }) {
            if(angle<90f || back==null) WalletCardView(card,cache,glow=glow,shadow=shadow)
            else Box(Modifier.fillMaxSize().graphicsLayer{rotationY=180f}) {
                // The back's text would blur into noise, so it glows with its edge colour instead.
                WalletGlow(glow,shape,Modifier.matchParentSize()) { Box(Modifier.matchParentSize().background(edge)) }
                Box(Modifier.matchParentSize().graphicsLayer{
                    this.shape=RoundedCornerShape(18.dp);clip=true;shadowElevation=5.dp.toPx()*shadow()
                }.clip(shape)) {
                    // Keep control slots during rotation so the number never changes size when motion ends.
                    WalletCardBack(back,Modifier.fillMaxSize(),state::edit,{value->state.reportCopy(copyPrivateValue(context,value,writeClip))},
                        actionsEnabled=enabled&&!state.angle.isRunning&&!state.editing&&!state.loading)
                }
            }
        }
    }
}

@Composable internal fun WalletCardBack(value: WalletPrivateContent, modifier: Modifier = Modifier,
    edit: (() -> Unit)? = null, copy: ((String) -> Unit)? = null, actionsEnabled:Boolean=true) {
    val base = Color(value.backColor.toInt())
    val ink = if(base.luminance()>.42f) Color(0xff16191d) else Color(0xfff4f5f7)
    BoxWithConstraints(modifier.background(Brush.linearGradient(listOf(lerp(base,Color.White,.07f),base,lerp(base,Color.Black,.12f))))
        .testTag("wallet-card-back")) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha=.06f),Color.Transparent)),
                size.width*.85f,Offset(size.width*.15f,-size.height*.3f))
        }
        val lines=walletNumberLines(value.number,value.showNumberSpacing)
        val number:@Composable (Modifier,androidx.compose.ui.unit.Dp?)->Unit={ modifier,height ->
            Row(modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                BoxWithConstraints(Modifier.weight(1f)) {
                    val style=privateFittedStyle(lines,maxWidth,25f,height)
                    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                        lines.forEachIndexed { index,line ->
                            Text(line,color=ink,style=style,maxLines=1,softWrap=false,textAlign=TextAlign.Center,
                                modifier=Modifier.fillMaxWidth().testTag("wallet-private-number-line-$index"))
                        }
                    }
                }
                if(copy!=null&&value.number.isNotEmpty()) PrivateCopyButton(stringResource(R.string.wallet_copy_card_number),"wallet-copy-number",ink,actionsEnabled){copy(value.number)}
            }
        }
        // These labels intentionally stay English on both locales, matching a physical card.
        // 这些标签在中文界面也保留英文；不要当成漏翻译，也不要改动用户填写的值。
        val fields:@Composable (Modifier)->Unit={ modifier ->
            Row(modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                if(value.expiry.isNotEmpty()) BackField("VALID THRU",value.expiry,ink,Modifier.weight(1.3f,fill=false).widthIn(max=136.dp),"expiry",copy,actionsEnabled)
                if(value.cvv1.isNotEmpty()) BackField("CVV1",value.cvv1,ink,Modifier.weight(1f,fill=false).widthIn(max=96.dp),"cvv1",copy,actionsEnabled)
                if(value.cvv2.isNotEmpty()) BackField("CVV2",value.cvv2,ink,Modifier.weight(1f,fill=false).widthIn(max=96.dp),"cvv2",copy,actionsEnabled)
            }
        }
        if(value.holder.isEmpty()) {
            number(Modifier.align(Alignment.Center).offset(y=if(lines.size>1)(-8).dp else 0.dp),null)
            fields(Modifier.align(Alignment.BottomCenter).padding(vertical=20.dp))
        } else {
            // The cardholder takes the bottom-left corner; the other fields move up and the number centres in
            // what is left below the edit button, shrinking if a small card leaves too little height.
            // 52.dp clears the edit button: 4.dp padding plus its 48.dp touch target.
            Column(Modifier.fillMaxSize().padding(top=52.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                    number(Modifier,maxHeight)
                }
                fields(Modifier)
                Row(Modifier.fillMaxWidth().padding(horizontal=24.dp)) {
                    BackField("CARDHOLDER",value.holder,ink,Modifier.widthIn(max=260.dp),"holder",copy,actionsEnabled)
                }
            }
        }
        val editLabel=stringResource(R.string.wallet_edit_private_card_details)
        if(edit!=null) IconButton(edit,Modifier.align(Alignment.TopStart).padding(4.dp).testTag("wallet-private-edit")
            .then(Modifier.semantics { contentDescription=editLabel }),enabled=actionsEnabled) {
            CompositionLocalProvider(LocalContentColor provides ink.copy(alpha=.82f)) {
                EditIcon(Modifier.size(19.dp))
            }
        }
    }
}

/** The copy button sits beside both lines, so its 48.dp touch target no longer pushes the value away from its label. */
@Composable private fun BackField(label:String,value:String,ink:Color,modifier:Modifier,tag:String,copy:((String)->Unit)?,enabled:Boolean) {
    Row(modifier,verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
            Text(label,color=ink.copy(alpha=.62f),fontSize=8.sp,lineHeight=10.sp,letterSpacing=.6.sp,maxLines=1)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Text(value,color=ink,style=privateFittedStyle(listOf(value),maxWidth,13f),maxLines=1,softWrap=false,modifier=Modifier.fillMaxWidth())
            }
        }
        if(copy!=null&&value.isNotEmpty()) PrivateCopyButton(when(tag) {
            "expiry" -> stringResource(R.string.wallet_copy_expiry_date)
            "holder" -> stringResource(R.string.wallet_copy_cardholder_name)
            else -> stringResource(R.string.wallet_copy, label)
        },"wallet-copy-$tag",ink,enabled){copy(value)}
    }
}

/** Largest size up to [maximum] at which every line fits [width] and, when given, all lines stack within [height]. */
@Composable private fun privateFittedStyle(lines:List<String>,width:androidx.compose.ui.unit.Dp,maximum:Float,
    height:androidx.compose.ui.unit.Dp?=null):TextStyle {
    val measurer=rememberTextMeasurer()
    val pixels=with(LocalDensity.current){width.toPx()}.toInt()-4
    val heightPixels=height?.let{with(LocalDensity.current){it.toPx()}.toInt()}
    // Use the exact rendered typography, including letter spacing. Binary search handles nonlinear font scaling.
    // 使用与渲染完全相同的字体/字距测量；大字体非线性缩放时禁止按比例推算，避免末位被裁切。
    val base=MaterialTheme.typography.bodyLarge.copy(fontFamily=FontFamily.Monospace,letterSpacing=0.sp)
    return remember(lines,pixels,heightPixels,base,maximum,measurer) {
        var lower=1f;var upper=maximum
        repeat(12) {
            val candidate=(lower+upper)/2
            val style=base.copy(fontSize=candidate.sp,lineHeight=(candidate*1.3f).sp)
            val sizes=lines.map{measurer.measure(it,style,softWrap=false,maxLines=1).size}
            if(sizes.all{it.width<=pixels}&&(heightPixels==null||sizes.sumOf{it.height}<=heightPixels))lower=candidate else upper=candidate
        }
        base.copy(fontSize=lower.sp,lineHeight=(lower*1.3f).sp)
    }
}

@Composable private fun PrivateCopyButton(label:String,tag:String,ink:Color,enabled:Boolean,copy:()->Unit) {
    IconButton(copy,Modifier.size(32.dp).testTag(tag).semantics{contentDescription=label},enabled=enabled) {
        FaIcon(FaIcons.copy,null,Modifier.size(15.dp),ink.copy(alpha=.62f))
    }
}

/** Writes a copied private value. UI tests record the clip here: an emulator's host clipboard sync replaces system clips within milliseconds. */
val LocalWalletClipboard=staticCompositionLocalOf<(android.content.Context,android.content.ClipData)->Unit> {
    { context,clip->context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip) }
}

private fun copyPrivateValue(context:android.content.Context,value:String,write:(android.content.Context,android.content.ClipData)->Unit):Boolean = try {
    // Explicit user action only. The clipboard is outside our encrypted vault; never copy on load/flip/save.
    // 仅允许主动点击复制。剪贴板不属于加密存储，禁止加载、翻面、保存时自动复制，也不记录复制值。
    val clip=android.content.ClipData.newPlainText("Valnook",value)
    clip.description.extras=android.os.PersistableBundle().apply{putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true)}
    write(context,clip)
    true
} catch (_: Exception) {
    // Never put exception messages or field values in feedback/logs.
    // 失败提示不得包含异常详情或字段内容，也不记录到日志。
    false
}
