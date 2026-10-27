package com.dawnyew.ui

import com.dawnyew.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource

/** Source type codes are not resource names: WOC! uses WOC.png; two posters are JPGs. */
internal fun sbtiPosterResource(code: String): DrawableResource = when (code) {
    "IMSB" -> Res.drawable.sbti_imsb
    "BOSS" -> Res.drawable.sbti_boss
    "MUM" -> Res.drawable.sbti_mum
    "FAKE" -> Res.drawable.sbti_fake
    "Dior-s" -> Res.drawable.sbti_dior_s
    "DEAD" -> Res.drawable.sbti_dead
    "ZZZZ" -> Res.drawable.sbti_zzzz
    "GOGO" -> Res.drawable.sbti_gogo
    "FUCK" -> Res.drawable.sbti_fuck
    "CTRL" -> Res.drawable.sbti_ctrl
    "HHHH" -> Res.drawable.sbti_hhhh
    "SEXY" -> Res.drawable.sbti_sexy
    "OJBK" -> Res.drawable.sbti_ojbk
    "JOKE-R" -> Res.drawable.sbti_joke_r
    "POOR" -> Res.drawable.sbti_poor
    "OH-NO" -> Res.drawable.sbti_oh_no
    "MONK" -> Res.drawable.sbti_monk
    "SHIT" -> Res.drawable.sbti_shit
    "THAN-K" -> Res.drawable.sbti_than_k
    "MALO" -> Res.drawable.sbti_malo
    "ATM-er" -> Res.drawable.sbti_atm_er
    "THIN-K" -> Res.drawable.sbti_thin_k
    "SOLO" -> Res.drawable.sbti_solo
    "LOVE-R" -> Res.drawable.sbti_love_r
    "WOC!" -> Res.drawable.sbti_woc
    "DRUNK" -> Res.drawable.sbti_drunk
    "IMFW" -> Res.drawable.sbti_imfw
    else -> error("Unknown SBTI type: $code")
}
