package com.timewarpscan.nativecamera.ui.language

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.timewarpscan.nativecamera.R
import com.timewarpscan.nativecamera.databinding.ItemLanguageBinding

class LanguageAdapter(
    private val languages: List<SelectLanguageActivity.Language>,
    private var selectedPosition: Int,
    private val onSelected: (Int) -> Unit
) : RecyclerView.Adapter<LanguageAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemLanguageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLanguageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val lang = languages[position]
        val isSelected = position == selectedPosition

        holder.binding.imgFlag.setImageResource(lang.flagRes)
        holder.binding.tvLanguageName.text = lang.name

        holder.binding.radioIndicator.setBackgroundResource(
            if (isSelected) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected
        )
        holder.binding.itemRoot.setBackgroundResource(
            if (isSelected) R.drawable.bg_language_item_selected else R.drawable.bg_language_item
        )

        holder.itemView.setOnClickListener {
            val prev = selectedPosition
            selectedPosition = holder.adapterPosition
            notifyItemChanged(prev)
            notifyItemChanged(selectedPosition)
            onSelected(selectedPosition)
        }
    }

    override fun getItemCount() = languages.size
}
