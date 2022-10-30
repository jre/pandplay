package net.joshe.pandplay.ui

import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.*
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import net.joshe.pandplay.R
import net.joshe.pandplay.databinding.FragmentPlayerBinding

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val libraryViewModel: LibraryViewModel by activityViewModels()

    private var playing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)

        libraryViewModel.controllerConnected.observe(viewLifecycleOwner) { ctrl ->
            Log.v("PLAYER", "controller connected")
            updatePlayPause(ctrl?.playbackState)
            if (ctrl == null) return@observe // XXX disconnect oncliclicklisteners?
            binding.playPauseButton.setOnClickListener {
                if (playing)
                    ctrl.transportControls.pause()
                else
                    ctrl.transportControls.play()
            }
            binding.nextSongButton.setOnClickListener { ctrl.transportControls.skipToNext() }
            binding.prevSongButton.setOnClickListener { ctrl.transportControls.skipToPrevious() }
            binding.stopButton.setOnClickListener { ctrl.transportControls.stop() }
        }
        libraryViewModel.playbackChanged.observe(viewLifecycleOwner) { updatePlayPause(it) }
        libraryViewModel.metadataChanged.observe(viewLifecycleOwner) { updateMetadata(it) }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        Log.v("PLAYER", "onDestroyView")
    }

    private fun updatePlayPause(state: PlaybackStateCompat? = null) {
        playing = when(state?.state) {
            null, STATE_BUFFERING, STATE_PLAYING, STATE_FAST_FORWARDING, STATE_REWINDING,
            STATE_SKIPPING_TO_NEXT, STATE_SKIPPING_TO_PREVIOUS, STATE_SKIPPING_TO_QUEUE_ITEM ->
                true
            else -> false
        }
        binding.playPauseButton.setImageResource(
            if (playing) R.drawable.ic_baseline_pause_24
            else R.drawable.ic_baseline_play_arrow_24)
    }

    private fun updateMetadata(metadata: MediaMetadataCompat?) {
        Log.v("PLAYER", "metadata changed to ${metadata?.description?.title}")
        metadata?.description?.let { md ->
            // XXX album title
            binding.songTitleView.text = md.title?:""
            binding.artistNameView.text = md.subtitle?:""
            if (md.iconUri is Uri)
                Glide.with(this)
                    .load(md.iconUri)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .placeholder(android.R.color.transparent)
                    .into(binding.songArtView)
            else {
                Glide.with(this).clear(binding.songArtView)
                binding.songArtView.setImageResource(android.R.color.transparent)
            }
        }
    }
}
