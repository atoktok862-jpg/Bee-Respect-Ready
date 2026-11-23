package com.beerespect.game

import android.app.Activity
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var gameView: BeeGameView
    private var mediaPlayer: MediaPlayer? = null
    private val loopHandler = Handler(Looper.getMainLooper())
    private var isPaused = false

    private val replayRunnable = Runnable {
        if (!isPaused) {
            try {
                mediaPlayer?.seekTo(0)
                mediaPlayer?.start()
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gameView = BeeGameView(this)
        setContentView(gameView)
        initMusic()
    }

    private fun initMusic() {
        try {
            val mp = MediaPlayer.create(this, R.raw.bg_music) ?: return
            mp.setVolume(0.75f, 0.75f)
            mp.setOnCompletionListener {
                loopHandler.removeCallbacks(replayRunnable)
                loopHandler.postDelayed(replayRunnable, 2000L)
            }
            mp.start()
            mediaPlayer = mp
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        isPaused = false
        gameView.onHostResume()
        try {
            if (mediaPlayer != null && mediaPlayer?.isPlaying == false) {
                mediaPlayer?.start()
            }
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        isPaused = true
        loopHandler.removeCallbacks(replayRunnable)
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (_: Exception) {
        }
        gameView.onHostPause()
        super.onPause()
    }

    override fun onDestroy() {
        loopHandler.removeCallbacks(replayRunnable)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        super.onDestroy()
    }
}
