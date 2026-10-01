package dev.valnook.designsystem
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
object Space { val xs=4.dp; val sm=8.dp; val md=16.dp; val lg=24.dp; val xl=32.dp }
private val light=lightColorScheme(primary=Color(0xFF486477),onPrimary=Color.White,
    background=Color(0xFFF7F8FA),surface=Color(0xFFF7F8FA),surfaceContainer=Color(0xFFEDF0F3),
    surfaceContainerLow=Color(0xFFF0F4F7),surfaceContainerHigh=Color(0xFFEAF0F4),surfaceContainerHighest=Color(0xFFE5EDF2),
    onSurface=Color(0xFF20272E),onSurfaceVariant=Color(0xFF4D5862),outline=Color(0xFF77838D),outlineVariant=Color(0xFFD4DEE6))
private val dark=darkColorScheme(primary=Color(0xFFA6C3D7),onPrimary=Color(0xFF153445),
    background=Color(0xFF181D22),surface=Color(0xFF181D22),surfaceContainer=Color(0xFF252C33),
    surfaceContainerLow=Color(0xFF20282F),surfaceContainerHigh=Color(0xFF29333C),surfaceContainerHighest=Color(0xFF303D48),
    onSurface=Color(0xFFE0E5EB),onSurfaceVariant=Color(0xFFBBC5CE),outline=Color(0xFF84919C),outlineVariant=Color(0xFF45535E))
private val typography=Typography(
    headlineSmall=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.SemiBold,fontSize=24.sp,lineHeight=32.sp),
    titleLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.SemiBold,fontSize=20.sp,lineHeight=28.sp),
    titleMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=16.sp,lineHeight=24.sp),
    bodyLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=24.sp),
    bodyMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=22.sp),
    bodySmall=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=12.sp,lineHeight=18.sp),
    labelLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=14.sp,lineHeight=20.sp))
@Composable fun ValnookTheme(dark_theme:Boolean=isSystemInDarkTheme(),content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=if(dark_theme) dark else light,typography=typography,
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp))) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background,
            contentColor=MaterialTheme.colorScheme.onSurface) {content()}
    }
}
