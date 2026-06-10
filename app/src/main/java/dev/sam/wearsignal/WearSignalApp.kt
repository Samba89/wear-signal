package dev.sam.wearsignal

import android.app.Application
import dev.sam.wearsignal.util.AndroidLogger
import org.signal.core.util.logging.Log

class WearSignalApp : Application() {
  override fun onCreate() {
    super.onCreate()
    Log.initialize(AndroidLogger())
    AppDeps.init(this)
  }
}
