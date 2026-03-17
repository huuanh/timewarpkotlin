package com.timewarpscan.nativecamera.ui.home.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.timewarpscan.nativecamera.databinding.ItemVideoBinding
import com.timewarpscan.nativecamera.model.VideoItem

class VideoAdapter(
    private val items: List<VideoItem>,
    private val onItemClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemVideoBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // Resolve drawable by name
        val resId = context.resources.getIdentifier(
            item.thumbnail, "drawable", context.packageName
        )
        if (resId != 0) {
            holder.binding.imgThumbnail.setImageResource(resId)
        }

        // Rounded corners via outline
        holder.binding.imgThumbnail.clipToOutline = true
        holder.binding.imgThumbnail.outlineProvider = RoundedOutlineProvider(12f)

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount() = items.size
}
