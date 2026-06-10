package dev.sam.wearsignal.crypto

import org.whispersystems.signalservice.api.SignalSessionLock
import java.util.concurrent.locks.ReentrantLock

/** Single global reentrant lock guarding all session mutations (mirrors Signal's ReentrantSessionLock). */
object SessionLock : SignalSessionLock {

  private val lock = ReentrantLock()

  override fun acquire(): SignalSessionLock.Lock {
    lock.lock()
    return SignalSessionLock.Lock { lock.unlock() }
  }
}
