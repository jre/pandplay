package net.joshe.pandplay.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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

        libraryViewModel.browserChanged.observe(viewLifecycleOwner) { ctrl ->
            Log.v("PLAYER", "controller connected")
            updatePlayPause(ctrl?.playbackState)
            if (ctrl == null) return@observe // XXX disconnect oncliclicklisteners?
            binding.playPauseButton.setOnClickListener {
                if (playing)
                    ctrl.pause()
                else
                    ctrl.play()
            }
            binding.nextSongButton.setOnClickListener { ctrl.seekToNext() }
            binding.prevSongButton.setOnClickListener { ctrl.seekToPrevious() }
            binding.stopButton.setOnClickListener { ctrl.stop() }
        }
        libraryViewModel.playerStateChanged.observe(viewLifecycleOwner) { updatePlayPause(it) }
        libraryViewModel.metadataChanged.observe(viewLifecycleOwner) { updateMetadata(it) }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        Log.v("PLAYER", "onDestroyView")
    }

    private fun updatePlayPause(@Player.State state: Int? = null) {
        playing = (state == Player.STATE_BUFFERING || state == Player.STATE_READY)
        binding.playPauseButton.setImageResource(
            if (playing) R.drawable.ic_baseline_pause_24
            else R.drawable.ic_baseline_play_arrow_24)
    }

    private fun updateMetadata(item: MediaItem?) {
        Log.v("PLAYER", "metadata changed to ${item?.mediaMetadata?.title}")
        item?.mediaMetadata?.let { md ->
            // XXX album title
            binding.songTitleView.text = md.title?:""
            binding.artistNameView.text = md.subtitle?:""
            if (md.artworkUri is Uri)
                Glide.with(this)
                    .load(md.artworkUri)
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
