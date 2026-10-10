package dev.valnook.feature.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable fun walletFlipLabel(toBack:Boolean)=if(toBack)wording("Show card back","翻到卡片背面")else wording("Show card front","翻到卡片正面")

@Composable fun WalletFlipIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(22.dp)) {
        val stroke = 1.6.dp.toPx()
        drawRoundRect(color, Offset(size.width*.32f,size.height*.17f),
            androidx.compose.ui.geometry.Size(size.width*.36f,size.height*.66f),
            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style=Stroke(stroke))
        drawArc(color, 25f, 285f, false, Offset(size.width*.02f,size.height*.31f),
            androidx.compose.ui.geometry.Size(size.width*.96f,size.height*.42f), style=Stroke(stroke,cap=StrokeCap.Round))
        drawLine(color,Offset(size.width*.91f,size.height*.3f),Offset(size.width*.94f,size.height*.48f),stroke,StrokeCap.Round)
        drawLine(color,Offset(size.width*.77f,size.height*.44f),Offset(size.width*.94f,size.height*.48f),stroke,StrokeCap.Round)
    }
}

@Composable internal fun WalletPaletteIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(22.dp)) {
        drawOval(color, Offset(size.width*.1f,size.height*.1f),
            androidx.compose.ui.geometry.Size(size.width*.8f,size.height*.8f),style=Stroke(1.6.dp.toPx()))
        listOf(.32f to .32f,.59f to .27f,.72f to .49f,.3f to .6f).forEach { (x,y) ->
            drawCircle(color,1.6.dp.toPx(),Offset(size.width*x,size.height*y))
        }
    }
}

@Composable internal fun WalletFlippingCard(card: WalletCard, cache: WalletImageCache,
    state: WalletPrivateState, modifier: Modifier, enabled: Boolean) {
    val shape = RoundedCornerShape(18.dp)
    val density = LocalDensity.current.density
    val back = state.content
    val angle = state.angle.value
    val context=LocalContext.current
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
            if(angle<90f || back==null) WalletCardView(card,cache)
            else Box(Modifier.fillMaxSize().graphicsLayer{rotationY=180f}
                .shadow(5.dp,shape).clip(shape)) {
                // Keep control slots during rotation so the number never changes size when motion ends.
                WalletCardBack(back,Modifier.fillMaxSize(),state::edit,{value->state.reportCopy(copyPrivateValue(context,value))},
                    actionsEnabled=enabled&&!state.angle.isRunning&&!state.editing&&!state.loading)
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
        Row(Modifier.align(Alignment.Center).offset(y=if(lines.size>1)(-8).dp else 0.dp)
            .fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val style=privateFittedStyle(lines,maxWidth,25f)
                Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                    lines.forEachIndexed { index,line ->
                        Text(line,color=ink,style=style,maxLines=1,softWrap=false,textAlign=TextAlign.Center,
                            modifier=Modifier.fillMaxWidth().testTag("wallet-private-number-line-$index"))
                    }
                }
            }
            if(copy!=null&&value.number.isNotEmpty()) PrivateCopyButton(wording("Copy card number","复制卡号"),"wallet-copy-number",ink,actionsEnabled){copy(value.number)}
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal=24.dp,vertical=20.dp),
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            // These three labels intentionally stay English on both locales, matching a physical card.
            // 这三个标签在中文界面也保留英文；不要当成漏翻译，也不要改动用户填写的值。
            if(value.expiry.isNotEmpty()) BackField("VALID THRU",value.expiry,ink,Modifier.weight(1.3f,fill=false).widthIn(max=136.dp),"expiry",copy,actionsEnabled)
            if(value.cvv1.isNotEmpty()) BackField("CVV1",value.cvv1,ink,Modifier.weight(1f,fill=false).widthIn(max=96.dp),"cvv1",copy,actionsEnabled)
            if(value.cvv2.isNotEmpty()) BackField("CVV2",value.cvv2,ink,Modifier.weight(1f,fill=false).widthIn(max=96.dp),"cvv2",copy,actionsEnabled)
        }
        val editLabel=wording("Edit private card details","编辑卡片背面")
        if(edit!=null) IconButton(edit,Modifier.align(Alignment.TopStart).padding(4.dp).testTag("wallet-private-edit")
            .then(Modifier.semantics { contentDescription=editLabel }),enabled=actionsEnabled) {
            CompositionLocalProvider(LocalContentColor provides ink.copy(alpha=.82f)) {
                EditIcon(Modifier.size(19.dp))
            }
        }
    }
}

@Composable private fun BackField(label:String,value:String,ink:Color,modifier:Modifier,tag:String,copy:((String)->Unit)?,enabled:Boolean) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(label,color=ink.copy(alpha=.62f),fontSize=9.sp,letterSpacing=.7.sp,maxLines=1)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                Text(value,color=ink,style=privateFittedStyle(listOf(value),maxWidth,13f),maxLines=1,softWrap=false,modifier=Modifier.fillMaxWidth())
            }
            if(copy!=null&&value.isNotEmpty()) PrivateCopyButton(if(tag=="expiry")wording("Copy expiry date","复制有效期")else wording("Copy $label","复制 $label"),"wallet-copy-$tag",ink,enabled){copy(value)}
        }
    }
}

@Composable private fun privateFittedStyle(lines:List<String>,width:androidx.compose.ui.unit.Dp,maximum:Float):TextStyle {
    val measurer=rememberTextMeasurer()
    val pixels=with(LocalDensity.current){width.toPx()}.toInt()-4
    // Use the exact rendered typography, including letter spacing. Binary search handles nonlinear font scaling.
    // 使用与渲染完全相同的字体/字距测量；大字体非线性缩放时禁止按比例推算，避免末位被裁切。
    val base=MaterialTheme.typography.bodyLarge.copy(fontFamily=FontFamily.Monospace,letterSpacing=0.sp)
    return remember(lines,pixels,base,maximum,measurer) {
        var lower=1f;var upper=maximum
        repeat(12) {
            val candidate=(lower+upper)/2
            val style=base.copy(fontSize=candidate.sp,lineHeight=(candidate*1.3f).sp)
            if(lines.all{measurer.measure(it,style,softWrap=false,maxLines=1).size.width<=pixels})lower=candidate else upper=candidate
        }
        base.copy(fontSize=lower.sp,lineHeight=(lower*1.3f).sp)
    }
}

@Composable private fun PrivateCopyButton(label:String,tag:String,ink:Color,enabled:Boolean,copy:()->Unit) {
    IconButton(copy,Modifier.size(32.dp).testTag(tag).semantics{contentDescription=label},enabled=enabled) {
        Canvas(Modifier.size(15.dp)) {
            val color=ink.copy(alpha=.62f);val stroke=Stroke(1.3.dp.toPx())
            drawRoundRect(color,Offset(size.width*.3f,size.height*.28f),androidx.compose.ui.geometry.Size(size.width*.58f,size.height*.62f),
                androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),style=stroke)
            drawLine(color,Offset(size.width*.13f,size.height*.68f),Offset(size.width*.13f,size.height*.1f),stroke.width,StrokeCap.Round)
            drawLine(color,Offset(size.width*.13f,size.height*.1f),Offset(size.width*.7f,size.height*.1f),stroke.width,StrokeCap.Round)
        }
    }
}

private fun copyPrivateValue(context:android.content.Context,value:String):Boolean = try {
    // Explicit user action only. The clipboard is outside our encrypted vault; never copy on load/flip/save.
    // 仅允许主动点击复制。剪贴板不属于加密存储，禁止加载、翻面、保存时自动复制，也不记录复制值。
    val clip=android.content.ClipData.newPlainText("Valnook",value)
    clip.description.extras=android.os.PersistableBundle().apply{putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true)}
    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
    true
} catch (_: Exception) {
    // Never put exception messages or field values in feedback/logs.
    // 失败提示不得包含异常详情或字段内容，也不记录到日志。
    false
}
