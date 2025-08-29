package net.joshe.pandplay.ui

import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.ListPreloader
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.util.ViewPreloadSizeProvider
import net.joshe.pandplay.R

class PlaylistItemAdapter(private val glideMgr: RequestManager, private val onClickHandler: (MediaItem?) -> Unit) :
    RecyclerView.Adapter<PlaylistItemAdapter.ViewHolder>() {
    private var songList: MutableList<MediaItem> = mutableListOf()
    private var selected: String? = null

    class ViewHolder(view: View, private val onClickHandler: (MediaItem?) -> Unit) : RecyclerView.ViewHolder(view) {
        val iconView: ImageView = view.findViewById(R.id.libraryIconView)
        val titleView: TextView = view.findViewById(R.id.songTitleView)
        val artistView: TextView = view.findViewById(R.id.artistNameView)
        var item: MediaItem? = null
        init {
            view.setOnClickListener {
                Log.v("RECYCLE", "clicked item ${item?.mediaId}")
                (bindingAdapter as? PlaylistItemAdapter)?.apply {
                    Log.v("RECYCLE", "changing selection from $selected to ${item?.mediaId}")
                    selectSong(item)
                }
                onClickHandler(item)
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val sizeProvider = ViewPreloadSizeProvider<Uri>()
        val modelProvider = object : ListPreloader.PreloadModelProvider<Uri> {
            override fun getPreloadItems(position: Int): MutableList<Uri>
                    = songList[position].mediaMetadata.artworkUri?.let{mutableListOf(it)}?:mutableListOf()
            override fun getPreloadRequestBuilder(art: Uri): RequestBuilder<*>
                    = glideMgr.load(art)
        }
        val maxPreload = 10 // XXX
        val preloader = RecyclerViewPreloader(glideMgr, modelProvider, sizeProvider, maxPreload)
        recyclerView.addOnScrollListener(preloader)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.library_item, parent, false),
            onClickHandler)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = songList[position]
        holder.item = item
        // XXX album title
        holder.titleView.text = item.mediaMetadata.title
        holder.artistView.text = item.mediaMetadata.subtitle
        if (item.mediaMetadata.artworkUri is Uri)
            glideMgr.load(item.mediaMetadata.artworkUri).placeholder(android.R.color.transparent).into(holder.iconView)
        else {
            glideMgr.clear(holder.iconView)
            holder.iconView.setImageResource(android.R.color.transparent)
        }
        holder.itemView.isSelected = (item. mediaId == selected)
    }

    override fun getItemCount(): Int = songList.size

    fun updateSongs(songs: List<MediaItem>) {
        if (songs.map{it.mediaId} == songList.map{it.mediaId}) {
            Log.v("RECYCLE", "skipping update, no songs changed")
            return
        }
        Log.v("RECYCLE", "updating playlist, ${songList.size} songs to ${songs.size}")
        songList.clear()
        songList.addAll(songs)
        notifyDataSetChanged()
    }

    private fun indexOfMediaId(mediaId: String?): Int
            = if (mediaId == null) -1 else songList.indexOfFirst {it.mediaId == mediaId}

    private fun selectSong(song: MediaItem?) {
        val prev = indexOfMediaId(selected)
        val next = indexOfMediaId(song?.mediaId)
        selected = song?.mediaId
        if (prev >= 0)
            notifyItemChanged(prev)
        if (next >= 0)
            notifyItemChanged(next)
    }

    fun selectMediaId(mediaId: String?) = selectSong(songList.find {it.mediaId == mediaId})
}
