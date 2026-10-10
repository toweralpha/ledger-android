package com.xiaozhangben.ledger.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.ui.theme.LedgerThemeFromSettings
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * Set when the user taps the "已记录" notification: the root composable reads it once
     * to open the transaction the notification is about, then clears it.
     *
     * Held as state rather than a plain val because `launchMode="singleTask"` means a
     * second notification tap arrives at [onNewIntent] **without** recreating the
     * activity. The previous version read the id once in `onCreate` and its `onNewIntent`
     * only called `setIntent`, so with the app already open the notification tap did
     * nothing at all — despite a comment claiming otherwise.
     *
     * Clearing it after use matters just as much: a deep link that survived would keep
     * dragging the user back to the transaction list, including after the activity is
     * recreated.
     */
    private val deepLinkTxnId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        deepLinkTxnId.value = intent?.getStringExtra(EXTRA_TXN_ID)

        setContent {
            val pendingTxnId by deepLinkTxnId.collectAsStateWithLifecycle()
            LedgerThemeFromSettings {
                LedgerRoot(
                    initialTransactionId = pendingTxnId,
                    onDeepLinkHandled = { deepLinkTxnId.value = null },
                )
            }
        }
    }

    /** A second notification tap while the activity is alive must still navigate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkTxnId.value = intent.getStringExtra(EXTRA_TXN_ID)
    }

    companion object {
        const val EXTRA_TXN_ID = "extra_txn_id"
    }
}
