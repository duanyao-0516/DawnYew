package com.dawnyew.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

internal data class ReferenceArtwork(val painter:Painter,val tinted:Boolean)
@Composable internal expect fun referenceArtwork(name:String):ReferenceArtwork?
