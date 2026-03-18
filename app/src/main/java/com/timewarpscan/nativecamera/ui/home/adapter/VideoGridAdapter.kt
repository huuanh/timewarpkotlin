package com.timewarpscan.nativecamera.ui.home.adapter

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.timewarpscan.nativecamera.databinding.ItemVideoGridBinding
import com.timewarpscan.nativecamera.model.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class VideoGridAdapter(
    private val onItemClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoGridAdapter.ViewHolder>() {

    private val items = mutableListOf<VideoItem>()
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()

    fun setItems(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemVideoGridBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // Rounded corners
        holder.binding.imgThumbnail.clipToOutline = true
        holder.binding.imgThumbnail.outlineProvider = RoundedOutlineProvider(14f)

        // Load real thumbnail from video
        val cached = thumbnailCache[item.id]
        if (cached != null) {
            holder.binding.imgThumbnail.setImageBitmap(cached)
        } else {
            holder.binding.imgThumbnail.setImageBitmap(null)
            val rawResId = context.resources.getIdentifier(
                item.thumbnail, "raw", context.packageName
            )
            if (rawResId != 0) {
                CoroutineScope(Dispatchers.Main).launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        extractThumbnailFromRaw(context, rawResId)
                    }
                    if (bitmap != null) {
                        thumbnailCache[item.id] = bitmap
                        if (holder.bindingAdapterPosition == position) {
                            holder.binding.imgThumbnail.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount() = items.size

    private fun extractThumbnailFromRaw(context: android.content.Context, rawResId: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val afd = context.resources.openRawResourceFd(rawResId)
            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
