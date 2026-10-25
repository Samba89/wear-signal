package dev.sam.wearsignal.net

import org.signal.network.NetworkResult
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.get
import org.whispersystems.signalservice.api.fromWebSocketRequest
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.push.AttachmentUploadForm
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.push.http.ResumableUploadSpec
import java.io.IOException

/**
 * Attachment upload endpoints.
 *
 * Trimmed copy of Signal-Android's `org.whispersystems.signalservice.api.attachment.AttachmentApi`
 * (not vendored): GET /v4/attachments/form/upload over the authenticated websocket for a signed
 * upload form, then a POST to the form's CDN (2 or 3) to open a resumable upload session. The
 * returned [ResumableUploadSpec] goes on the attachment stream handed to
 * `SignalServiceMessageSender.uploadAttachment`, which encrypts, pads, and uploads it.
 */
class AttachmentApi(
  private val authWebSocket: SignalWebSocket.AuthenticatedWebSocket,
  private val pushServiceSocket: PushServiceSocket
) {

  /**
   * A signed upload form for an attachment of [uploadSizeBytes] (the encrypted, padded size —
   * the server rejects forms for attachments over its limit). Needs [authWebSocket] connected.
   */
  fun getAttachmentV4UploadForm(uploadSizeBytes: Long): NetworkResult<AttachmentUploadForm> {
    val request = WebSocketRequestMessage.get("/v4/attachments/form/upload?uploadLength=$uploadSizeBytes")
    return NetworkResult.fromWebSocketRequest(authWebSocket, request, AttachmentUploadForm::class)
  }

  /** Opens the resumable upload session on the form's CDN; generates the attachment key and IV. */
  @Throws(IOException::class)
  fun getResumableUploadSpec(uploadForm: AttachmentUploadForm): ResumableUploadSpec {
    return pushServiceSocket.getResumableUploadSpec(uploadForm)
  }
}
