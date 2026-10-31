package com.vela.android.lab.core.marketdataset.runtime

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Test APK only. Select ONE separately authorized stage; never automatically continue to SQLite. */
class MarketDatasetR1Runner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onCreate(arguments: Bundle) {
        MarketDatasetR1Authorization.validate(arguments.keySet().associateWith(arguments::getString))
        super.onCreate(arguments)
    }

    companion object {
        const val PREFLIGHT_CLASS = MarketDatasetR1Authorization.PREFLIGHT_CLASS
        const val SQLITE_CLASS = MarketDatasetR1Authorization.SQLITE_CLASS
        const val AUTHORIZATION_ARGUMENT = MarketDatasetR1Authorization.ARGUMENT
        const val PREFLIGHT_AUTHORIZATION_VALUE = MarketDatasetR1Authorization.PREFLIGHT_VALUE
        const val SQLITE_AUTHORIZATION_VALUE = MarketDatasetR1Authorization.SQLITE_VALUE
    }
}
