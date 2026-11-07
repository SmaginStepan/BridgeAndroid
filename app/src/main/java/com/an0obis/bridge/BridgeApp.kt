package com.an0obis.bridge

import android.app.Application
import com.an0obis.bridge.model.BridgeStorage

/** Application object: initialises app storage. Game state will live here once the engine exists. */
class BridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        BridgeStorage.init(filesDir)
    }

    /** Called when the app goes to the background. */
    fun saveAll() {
        // nothing to save yet
    }
}
