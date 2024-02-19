package su.sres.securesms.conversation

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.lifecycle.Observer
import su.sres.securesms.R
import su.sres.securesms.components.AudioView
import su.sres.securesms.components.voice.VoiceNotePlaybackState
import su.sres.securesms.database.DraftDatabase
import su.sres.securesms.mms.AudioSlide

class VoiceNoteDraftView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {

  var listener: Listener? = null

  var draft: DraftDatabase.Draft? = null
    private set

  private lateinit var audioView: AudioView

  val playbackStateObserver: Observer<VoiceNotePlaybackState>
    get() = audioView.playbackStateObserver

  init {
    inflate(context, R.layout.voice_note_draft_view, this)

    val delete: View = findViewById(R.id.voice_note_draft_delete)

    delete.setOnClickListener {
      if (draft != null) {
        val uri = audioView.audioSlideUri
        if (uri != null) {
          listener?.onVoiceNoteDraftDelete(uri)
        }
      }
    }

    audioView = findViewById(R.id.voice_note_audio_view)
  }

  fun clearDraft() {
    this.draft = null
  }

  fun setDraft(draft: DraftDatabase.Draft) {
    audioView.setAudio(
      AudioSlide.createFromVoiceNoteDraft(context, draft),
      AudioViewCallbacksAdapter(),
      true,
      false
    )

    this.draft = draft
  }

  private inner class AudioViewCallbacksAdapter : AudioView.Callbacks {
    override fun onPlay(audioUri: Uri, progress: Double) {
      listener?.onVoiceNoteDraftPlay(audioUri, progress)
    }

    override fun onPause(audioUri: Uri) {
      listener?.onVoiceNoteDraftPause(audioUri)
    }

    override fun onSeekTo(audioUri: Uri, progress: Double) {
      listener?.onVoiceNoteDraftSeekTo(audioUri, progress)
    }

    override fun onStopAndReset(audioUri: Uri) {
      throw UnsupportedOperationException()
    }

    override fun onProgressUpdated(durationMillis: Long, playheadMillis: Long) = Unit

    override fun onSpeedChanged(speed: Float, isPlaying: Boolean) = Unit
  }

  interface Listener {
    fun onVoiceNoteDraftPlay(audioUri: Uri, progress: Double)
    fun onVoiceNoteDraftPause(audioUri: Uri)
    fun onVoiceNoteDraftSeekTo(audioUri: Uri, progress: Double)
    fun onVoiceNoteDraftDelete(audioUri: Uri)
  }
}