package dev.valnook.designsystem
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
object Space { val xs=4.dp; val sm=6.dp; val md=12.dp; val lg=20.dp; val xl=24.dp }
private val light=lightColorScheme(
    primary=Color(0xFF303034),onPrimary=Color.White,
    primaryContainer=Color(0xFFE3E3E7),onPrimaryContainer=Color(0xFF202023),
    secondary=Color(0xFF56565D),onSecondary=Color.White,
    secondaryContainer=Color.White,onSecondaryContainer=Color(0xFF202023),
    tertiary=Color(0xFF626269),onTertiary=Color.White,
    tertiaryContainer=Color(0xFFE8E8EC),onTertiaryContainer=Color(0xFF202023),
    background=Color(0xFFF2F2F4),onBackground=Color(0xFF202023),
    surface=Color(0xFFF2F2F4),onSurface=Color(0xFF202023),
    surfaceDim=Color(0xFFE0E0E4),surfaceBright=Color(0xFFFAFAFC),
    surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF8F8FA),
    surfaceContainer=Color(0xFFE8E8EC),surfaceContainerHigh=Color(0xFFE2E2E7),surfaceContainerHighest=Color(0xFFDCDCE2),
    surfaceVariant=Color(0xFFE8E8EC),onSurfaceVariant=Color(0xFF626269),
    outline=Color(0xFF787880),outlineVariant=Color(0xFFD2D2D8),surfaceTint=Color(0xFF626269),
    inverseSurface=Color(0xFF303034),inverseOnSurface=Color(0xFFF2F2F4),inversePrimary=Color(0xFFD9D9DF))
private val dark=darkColorScheme(
    primary=Color(0xFFE8E8ED),onPrimary=Color(0xFF202023),
    primaryContainer=Color(0xFF3A3A40),onPrimaryContainer=Color(0xFFF2F2F4),
    secondary=Color(0xFFC4C4CC),onSecondary=Color(0xFF202023),
    secondaryContainer=Color(0xFF505057),onSecondaryContainer=Color(0xFFF7F7FA),
    tertiary=Color(0xFFB8B8C0),onTertiary=Color(0xFF202023),
    tertiaryContainer=Color(0xFF36363C),onTertiaryContainer=Color(0xFFF2F2F4),
    background=Color(0xFF111113),onBackground=Color(0xFFF2F2F4),
    surface=Color(0xFF111113),onSurface=Color(0xFFF2F2F4),
    surfaceDim=Color(0xFF111113),surfaceBright=Color(0xFF39393F),
    surfaceContainerLowest=Color(0xFF0D0D0F),surfaceContainerLow=Color(0xFF1C1C1F),
    surfaceContainer=Color(0xFF262629),surfaceContainerHigh=Color(0xFF303035),surfaceContainerHighest=Color(0xFF3A3A40),
    surfaceVariant=Color(0xFF3A3A40),onSurfaceVariant=Color(0xFFA6A6AE),
    outline=Color(0xFF888890),outlineVariant=Color(0xFF44444B),surfaceTint=Color(0xFFB8B8C0),
    inverseSurface=Color(0xFFE8E8ED),inverseOnSurface=Color(0xFF202023),inversePrimary=Color(0xFF56565D))
private val typography=Typography(
    headlineMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.SemiBold,fontSize=32.sp,lineHeight=40.sp),
    headlineSmall=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.SemiBold,fontSize=24.sp,lineHeight=32.sp),
    titleLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.SemiBold,fontSize=20.sp,lineHeight=28.sp),
    titleMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=16.sp,lineHeight=24.sp),
    bodyLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=24.sp),
    bodyMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=22.sp),
    bodySmall=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=12.sp,lineHeight=18.sp),
    labelLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=14.sp,lineHeight=20.sp))
@Composable fun ValnookTheme(dark_theme:Boolean=isSystemInDarkTheme(),redGain:Boolean=false,content:@Composable ()->Unit) {
    val green = if (dark_theme) Color(0xFF49C996) else Color(0xFF168457)
    val red = if (dark_theme) Color(0xFFFF737D) else Color(0xFFC62828)
    val palette = GainLossPalette(if(redGain) red else green,if(redGain) green else red,
        if(dark_theme) dark.onSurfaceVariant else light.onSurfaceVariant)
    MaterialTheme(colorScheme=if(dark_theme) dark else light,typography=typography,
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp))) {
        CompositionLocalProvider(LocalGainLossPalette provides palette) {
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background,
                contentColor=MaterialTheme.colorScheme.onSurface) {content()}
        }
    }
}
