package dev.sam.wearsignal.calls

import org.signal.ringrtc.Remote

/** RingRTC's opaque handle for the other party of a 1:1 call: just the peer's ACI. */
class RemotePeer(val aci: String) : Remote {
  override fun recipientEquals(other: Remote?): Boolean = other is RemotePeer && other.aci == aci
  override fun toString(): String = aci.take(8)
}
