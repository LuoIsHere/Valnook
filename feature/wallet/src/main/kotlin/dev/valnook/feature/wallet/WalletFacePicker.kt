package dev.valnook.feature.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.valnook.domain.model.*
import kotlinx.coroutines.flow.first
import kotlin.math.floor

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
    val backgrounds=listOf(wording("Default","默认"))+WalletFaceBackground.entries.map { background ->
        when(background) {
                WalletFaceBackground.SOLID -> wording("Solid","纯色")
                WalletFaceBackground.LINEAR -> wording("Gradient","渐变")
                WalletFaceBackground.RADIAL -> wording("Glow","柔光")
        }
    }
    FaceChipRow(backgrounds,if(value!=null)value.background.ordinal+1 else if(!hasImage)0 else -1,enabled,"wallet-face-background") { index ->
        onChange(if(index==0)null else style.copy(background=WalletFaceBackground.entries[index-1]))
    }
    if(value!=null) {
        val shapes=WalletFaceGeometry.entries.map { shape ->
            when(shape) {
                    WalletFaceGeometry.NONE -> wording("Plain","无图形")
                    WalletFaceGeometry.RINGS -> wording("Rings","圆环")
                    WalletFaceGeometry.ARC -> wording("Arc","圆弧")
                    WalletFaceGeometry.DIAGONAL -> wording("Stripes","斜纹")
                    WalletFaceGeometry.CIRCLES -> wording("Circles","圆形")
                    WalletFaceGeometry.BLOCKS -> wording("Blocks","方块")
            }
        }
        FaceChipRow(shapes,style.geometry.ordinal,enabled,"wallet-face-geometry") { index ->
            onChange(style.copy(geometry=WalletFaceGeometry.entries[index]))
        }
        FaceColorRow(wording("Base","底色"),style.base,enabled){onChange(style.copy(base=it))}
        if(style.background!=WalletFaceBackground.SOLID)FaceColorRow(wording("Gradient end","渐变尾色"),style.secondary,enabled){onChange(style.copy(secondary=it))}
        if(style.geometry!=WalletFaceGeometry.NONE)FaceColorRow(wording("Pattern","图形颜色"),style.accent,enabled){onChange(style.copy(accent=it))}
    }
}

@Composable private fun FaceColorRow(label:String,selected:Long,enabled:Boolean,onChange:(Long)->Unit) {
    Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    val colors=listOf(0xff303740L,0xff687983L,0xff788a83L,0xffb2a08bL,0xffa87c7cL,0xff827998L,0xff839db9L,0xffe4e8edL)
    FaceOptionRow(colors.size,colors.indexOf(selected),44.dp,4.dp,"wallet-face-color-$label") { index,modifier ->
        val color=colors[index]
        Box(modifier,contentAlignment=Alignment.Center) {
            val description="$label #${color.toString(16).takeLast(6).uppercase()}"
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled=enabled,role=Role.RadioButton){onChange(color)}
                .semantics { contentDescription=description; this.selected=selected==color }.padding(6.dp)
                .border(if(selected==color)2.dp else 0.dp,if(selected==color)MaterialTheme.colorScheme.onSurface else Color.Transparent,CircleShape)
                .padding(4.dp).background(Color(color.toInt()),CircleShape))
        }
    }
}

@Composable private fun FaceChipRow(labels:List<String>,selected:Int,enabled:Boolean,tag:String,onSelect:(Int)->Unit) {
    val measurer=rememberTextMeasurer()
    val textStyle=MaterialTheme.typography.labelLarge
    val density=LocalDensity.current
    val minimum=with(density) { labels.maxOf { measurer.measure(it,textStyle).size.width }.toDp()+32.dp }.coerceAtLeast(64.dp)
    FaceOptionRow(labels.size,selected,minimum,8.dp,tag) { index,modifier ->
        FilterChip(selected==index,{onSelect(index)},modifier=modifier,enabled=enabled,
            label={Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){Text(labels[index],maxLines=1)}})
    }
}

// Reveal a real next option, not an overlay. Keep text readable at large font sizes.
// 露出真实的下一项，不叠加遮罩；大字体下减少完整选项数量，避免压缩文字。
@Composable private fun FaceOptionRow(
    count:Int,selected:Int,minimum:Dp,gap:Dp,tag:String,
    content:@Composable (Int,Modifier)->Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val overflows=minimum*count+gap*(count-1)>maxWidth
        val visible=if(overflows)floor((maxWidth-24.dp)/(minimum+gap)).toInt().coerceAtLeast(1) else count
        val width=if(overflows)((maxWidth-24.dp)/visible-gap).coerceAtLeast(minimum) else minimum
        val state=rememberLazyListState(initialFirstVisibleItemIndex=(selected-visible+1).coerceAtLeast(0))
        val density=LocalDensity.current
        val stride=with(density){(width+gap).toPx()}
        val itemWidth=with(density){width.toPx()}
        val viewport=with(density){maxWidth.toPx()}
        val trailingHint=with(density){(gap+24.dp).toPx()}
        LaunchedEffect(selected,stride,viewport) {
            if(selected !in 0 until count)return@LaunchedEffect
            snapshotFlow { state.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
            val left=(selected-state.firstVisibleItemIndex)*stride-state.firstVisibleItemScrollOffset
            val distance=when {
                left<0 -> left
                left+itemWidth>viewport -> left+itemWidth-viewport+if(selected<count-1)trailingHint else 0f
                else -> 0f
            }
            if(distance!=0f)state.animateScrollBy(distance,tween(280,easing=FastOutSlowInEasing))
        }
        LazyRow(Modifier.fillMaxWidth().testTag(tag),state=state,horizontalArrangement=Arrangement.spacedBy(gap)) {
            items(count,key={it}) { index -> content(index,Modifier.width(width).testTag("$tag-$index")) }
        }
    }
}
