package com.sadurni.attacca.ui.chain

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

/**
 * While a list is being made or started (it can take a while the first time, or with many tracks):
 * a small notice at the top of every screen, saying when it takes only the included tracks. While
 * the list is still being made, its X stops it.
 */
@Composable
fun ListMakingBadge(viewModel: PlayerViewModel) {
    val busy by viewModel.listBusy.collectAsStateWithLifecycle()
    val making by LocalContext.current.app.chainLists.making.collectAsStateWithLifecycle()
    if (!busy) return
    val colors = LocalAttaccaColors.current
    Popup(alignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .background(colors.surface, RoundedCornerShape(20.dp))
                    .border(1.dp, LocalAccent.current, RoundedCornerShape(20.dp))
                    .padding(start = 16.dp, end = if (making != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (making != null) 8.dp else 12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), color = LocalAccent.current, strokeWidth = 2.dp)
                Column {
                    Text(stringResource(R.string.list_making), fontSize = AttaccaType.secondary, fontWeight = FontWeight.SemiBold, color = colors.text)
                    if (making?.onlyIncluded == true) {
                        Text(stringResource(R.string.making_only_included), fontSize = AttaccaType.caption, color = colors.textGrayed)
                    }
                }
                if (making != null) {
                    IconButton(onClick = viewModel::cancelList, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.list_making_cancel), tint = colors.text)
                    }
                }
            }
        }
    }
}
