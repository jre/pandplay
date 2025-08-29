package net.joshe.pandplay.ui

import android.os.Bundle
import android.util.Log
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import net.joshe.pandplay.databinding.FragmentPlaylistBinding
import net.joshe.pandplay.media.mediaItems

class PlaylistFragment : Fragment() {
    private var _binding: FragmentPlaylistBinding? = null

    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val model: LibraryViewModel by activityViewModels()

        _binding = FragmentPlaylistBinding.inflate(inflater, container, false)
        val adapter = PlaylistItemAdapter(Glide.with(this)) { item ->
            Log.v("SONGS", "clicked song ${item?.mediaId} - ${item?.mediaMetadata?.title}")
            item?.mediaId?.let {model.skipToMediaId(it)}
        }
        binding.songlistView.adapter = adapter
        binding.songlistView.layoutManager = LinearLayoutManager(activity)
        binding.songlistView.addItemDecoration(DividerItemDecoration(context , VERTICAL))
        model.playlistChanged.observe(viewLifecycleOwner) { tl ->
            viewLifecycleOwner.lifecycleScope.launch {
                adapter.updateSongs(tl.mediaItems())
            }
        }
        model.metadataChanged.observe(viewLifecycleOwner) {
            Log.v("SONGS", "metadata changed to ${it?.mediaId}")
            adapter.selectMediaId(it?.mediaId)
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
