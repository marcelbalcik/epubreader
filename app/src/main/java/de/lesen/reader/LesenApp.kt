package de.lesen.reader

import android.app.Application
import de.lesen.reader.di.AppContainer

class LesenApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
