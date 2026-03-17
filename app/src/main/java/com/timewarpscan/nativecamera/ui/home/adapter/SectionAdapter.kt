package com.timewarpscan.nativecamera.ui.home.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.timewarpscan.nativecamera.core.ui.NativeAdViewComponent
import com.timewarpscan.nativecamera.databinding.ItemNativeAdBinding
import com.timewarpscan.nativecamera.databinding.ItemSectionBinding
import com.timewarpscan.nativecamera.model.Section
import com.timewarpscan.nativecamera.model.VideoItem

class SectionAdapter(
    private val onItemClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_AD = 1
    }

    private sealed class ListItem {
        data class SectionItem(val section: Section) : ListItem()
        data object AdItem : ListItem()
    }

    private val items = mutableListOf<ListItem>()
    private val viewPool = RecyclerView.RecycledViewPool()

    fun setSections(sections: List<Section>) {
        items.clear()
        sections.forEachIndexed { index, section ->
            items.add(ListItem.SectionItem(section))
            // Insert native ad after first section
            if (index == 0) {
                items.add(ListItem.AdItem)
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is ListItem.SectionItem -> TYPE_SECTION
        is ListItem.AdItem -> TYPE_AD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_AD -> {
                val binding = ItemNativeAdBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                AdViewHolder(binding)
            }
            else -> {
                val binding = ItemSectionBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                SectionViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.SectionItem -> (holder as SectionViewHolder).bind(item.section)
            is ListItem.AdItem -> (holder as AdViewHolder).bind()
        }
    }

    override fun getItemCount() = items.size

    inner class SectionViewHolder(private val binding: ItemSectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(section: Section) {
            binding.tvSectionTitle.text = section.title

            binding.rvItems.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                adapter = VideoAdapter(section.items, onItemClick)
                setRecycledViewPool(viewPool)
            }
        }
    }

    inner class AdViewHolder(private val binding: ItemNativeAdBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            binding.nativeAdView.loadAndBind()
        }
    }
}
