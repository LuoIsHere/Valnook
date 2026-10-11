package dev.valnook.feature.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.valnook.domain.model.*

// Public front artwork only. Never read or write private back/edge colors here.
// 仅处理可备份的正面样式，禁止引用私密卡背、侧面配色。
@Composable internal fun WalletFaceCanvas(style: WalletFaceStyle, modifier: Modifier) {
    Canvas(modifier) {
        val base=Color(style.base.toInt()); val secondary=Color(style.secondary.toInt())
        val accent=Color(style.accent.toInt()); val w=size.width; val h=size.height
        when(style.background) {
            WalletFaceBackground.SOLID -> drawRect(base)
            WalletFaceBackground.LINEAR -> drawRect(Brush.linearGradient(listOf(base,secondary),Offset.Zero,Offset(w,h)))
            WalletFaceBackground.RADIAL -> drawRect(Brush.radialGradient(listOf(base,secondary),Offset(w*.2f,h*.15f),w))
        }
        val ink=accent.copy(alpha=.22f)
        when(style.geometry) {
            WalletFaceGeometry.NONE -> Unit
            WalletFaceGeometry.RINGS -> repeat(4) { i -> drawCircle(ink,w*(.22f+i*.11f),Offset(w*.88f,h*.78f),style=Stroke(w*.015f)) }
            WalletFaceGeometry.ARC -> {
                drawCircle(ink,w*.53f,Offset(w*.95f,h*.12f))
                drawCircle(secondary.copy(alpha=.6f),w*.39f,Offset(w*.95f,h*.12f))
            }
            WalletFaceGeometry.DIAGONAL -> repeat(3) { i -> drawLine(ink,Offset(w*(.42f+i*.22f),-h*.15f),Offset(w*(.02f+i*.22f),h*1.15f),w*.11f) }
            WalletFaceGeometry.CIRCLES -> {
                drawCircle(ink,w*.3f,Offset(w*.2f,h*.2f))
                drawCircle(ink,w*.37f,Offset(w*.85f,h*.9f))
            }
            WalletFaceGeometry.BLOCKS -> repeat(4) { i ->
                drawRoundRect(ink,Offset(w*(.35f+i*.14f),h*(.15f+i*.16f)),Size(w*.34f,h*.43f),androidx.compose.ui.geometry.CornerRadius(w*.025f))
            }
        }
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha=.12f),Color.Transparent,Color.Black.copy(alpha=.06f))))
    }
}

@Composable internal fun WalletFacePicker(value:WalletFaceStyle?,enabled:Boolean,hasImage:Boolean=false,onChange:(WalletFaceStyle?)->Unit) {
    val style=value?:WalletFaceStyle()
    Text(wording("Built-in artwork","内置卡面"),style=MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(value==null&&!hasImage,{onChange(null)},enabled=enabled,label={Text(wording("Default","默认"))})
        WalletFaceBackground.entries.forEach { background ->
            val title=when(background) {
                WalletFaceBackground.SOLID -> wording("Solid","纯色")
                WalletFaceBackground.LINEAR -> wording("Gradient","渐变")
                WalletFaceBackground.RADIAL -> wording("Glow","柔光")
            }
            FilterChip(value?.background==background,{onChange(style.copy(background=background))},enabled=enabled,label={Text(title)})
        }
    }
    if(value!=null) {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            WalletFaceGeometry.entries.forEach { shape ->
                val title=when(shape) {
                    WalletFaceGeometry.NONE -> wording("Plain","无图形")
                    WalletFaceGeometry.RINGS -> wording("Rings","圆环")
                    WalletFaceGeometry.ARC -> wording("Arc","圆弧")
                    WalletFaceGeometry.DIAGONAL -> wording("Stripes","斜纹")
                    WalletFaceGeometry.CIRCLES -> wording("Circles","圆形")
                    WalletFaceGeometry.BLOCKS -> wording("Blocks","方块")
                }
                FilterChip(style.geometry==shape,{onChange(style.copy(geometry=shape))},enabled=enabled,label={Text(title)})
            }
        }
        FaceColorRow(wording("Base","底色"),style.base,enabled){onChange(style.copy(base=it))}
        if(style.background!=WalletFaceBackground.SOLID)FaceColorRow(wording("Gradient end","渐变尾色"),style.secondary,enabled){onChange(style.copy(secondary=it))}
        if(style.geometry!=WalletFaceGeometry.NONE)FaceColorRow(wording("Pattern","图形颜色"),style.accent,enabled){onChange(style.copy(accent=it))}
    }
}

@Composable private fun FaceColorRow(label:String,selected:Long,enabled:Boolean,onChange:(Long)->Unit) {
    Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        listOf(0xff303740L,0xff687983L,0xff788a83L,0xffb2a08bL,0xffa87c7cL,0xff827998L,0xff839db9L,0xffe4e8edL).forEach { color ->
            val description="$label #${color.toString(16).takeLast(6).uppercase()}"
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled=enabled,role=Role.RadioButton){onChange(color)}
                .semantics { contentDescription=description; this.selected=selected==color }.padding(6.dp)
                .border(if(selected==color)2.dp else 0.dp,if(selected==color)MaterialTheme.colorScheme.onSurface else Color.Transparent,CircleShape)
                .padding(4.dp).background(Color(color.toInt()),CircleShape))
        }
    }
}
