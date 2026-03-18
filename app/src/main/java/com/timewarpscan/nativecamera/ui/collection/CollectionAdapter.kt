package com.timewarpscan.nativecamera.ui.collection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.timewarpscan.nativecamera.databinding.ItemCollectionBinding
import com.timewarpscan.nativecamera.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class CollectionAdapter(
    private val context: Context,
    private val onItemClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<CollectionAdapter.ViewHolder>() {

    private val items = mutableListOf<MediaItem>()
    private val thumbnailCache = ConcurrentHashMap<Long, Bitmap>()

    fun setItems(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemCollectionBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCollectionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        // Make items square based on column width
        val displayWidth = parent.context.resources.displayMetrics.widthPixels
        val itemSize = displayWidth / 3  // 3 columns
        binding.root.layoutParams = ViewGroup.LayoutParams(itemSize, itemSize)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.binding.imgThumbnail.setImageBitmap(null)
        holder.binding.playOverlay.visibility =
            if (item.isVideo) View.VISIBLE else View.GONE

        val cached = thumbnailCache[item.id]
        if (cached != null) {
            holder.binding.imgThumbnail.setImageBitmap(cached)
        } else {
            CoroutineScope(Dispatchers.Main).launch {
                val bitmap = withContext(Dispatchers.IO) {
                    loadThumbnail(item)
                }
                if (bitmap != null) {
                    thumbnailCache[item.id] = bitmap
                    if (holder.bindingAdapterPosition == position) {
                        holder.binding.imgThumbnail.setImageBitmap(bitmap)
                    }
                }
            }
        }

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    private fun loadThumbnail(item: MediaItem): Bitmap? {
        return if (item.isVideo) loadVideoThumbnail(item) else loadImageThumbnail(item)
    }

    private fun loadImageThumbnail(item: MediaItem): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(item.uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            opts.inSampleSize = computeSampleSize(opts.outWidth, opts.outHeight, 400)
            opts.inJustDecodeBounds = false
            context.contentResolver.openInputStream(item.uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadVideoThumbnail(item: MediaItem): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, item.uri)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun computeSampleSize(width: Int, height: Int, targetSize: Int): Int {
        var inSampleSize = 1
        val maxDim = maxOf(width, height)
        while (maxDim / (inSampleSize * 2) >= targetSize) {
            inSampleSize *= 2
        }
        return inSampleSize
    }
}
