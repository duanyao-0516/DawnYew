package com.dawnyew.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(primary=Color(0xFF006A60),onPrimary=Color.White,primaryContainer=Color(0xFF9EF2E4),onPrimaryContainer=Color(0xFF00201C),secondary=Color(0xFF4A635E),secondaryContainer=Color(0xFFCDE8DF),onSecondaryContainer=Color(0xFF06201A),tertiary=Color(0xFF80552A),tertiaryContainer=Color(0xFFD0E8E1),onTertiaryContainer=Color(0xFF0B211C),background=Color(0xFFF8FAF7),surface=Color(0xFFF8FAF7),surfaceVariant=Color(0xFFDAE5E0))
private val Dark = darkColorScheme(primary=Color(0xFF83D5C6),onPrimary=Color(0xFF003731),primaryContainer=Color(0xFF005047),onPrimaryContainer=Color(0xFF9EF2E4),secondary=Color(0xFFB1CCC4),secondaryContainer=Color(0xFF324C43),onSecondaryContainer=Color(0xFFCDE8DF),tertiary=Color(0xFFE5BD91),tertiaryContainer=Color(0xFF354D45),onTertiaryContainer=Color(0xFFD0E8E1),background=Color(0xFF101513),surface=Color(0xFF101513),surfaceVariant=Color(0xFF3F4945))
@Composable fun DawnYewTheme(mode:String="system",content:@Composable ()->Unit) {
    val dark=mode=="dark" || (mode=="system" && isSystemInDarkTheme())
    MaterialTheme(colorScheme=if(dark) Dark else Light,typography=Typography(),content=content)
}
