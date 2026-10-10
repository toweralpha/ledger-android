package com.xiaozhangben.ledger.di

import android.content.Context
import com.xiaozhangben.ledger.LedgerApp

/**
 * Access point for the container from components the system instantiates itself
 * (notification listener, boot receiver, widget receiver).
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as LedgerApp).container
