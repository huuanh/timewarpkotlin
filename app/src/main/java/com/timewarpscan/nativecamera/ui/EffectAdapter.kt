package com.timewarpscan.nativecamera.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.timewarpscan.nativecamera.R

/**
 * Data class for each warp effect.
 *
 * @param id unique identifier matching the TimeWarpScan RN project
 * @param name display name shown under the thumbnail
 * @param imageResId drawable resource for the effect thumbnail
 */
data class Effect(
    val id: String,
    val name: String,
    val imageResId: Int
)

/**
 * RecyclerView adapter for the effect selector grid.
 * Mirrors the TimeWarpScan React Native EffectSelector / EffectButton components.
 *
 * - 4 columns grid
 * - Selected effect gets a gold (#D4A94B) border ring
 * - Tapping an effect notifies [onEffectSelected]
 */
class EffectAdapter(
    private val effects: List<Effect>,
    private var selectedId: String,
    private val onEffectSelected: (Effect) -> Unit
) : RecyclerView.Adapter<EffectAdapter.EffectViewHolder>() {

    fun setSelected(id: String) {
        val oldIndex = effects.indexOfFirst { it.id == selectedId }
        val newIndex = effects.indexOfFirst { it.id == id }
        selectedId = id
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EffectViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_effect, parent, false)
        return EffectViewHolder(view)
    }

    override fun onBindViewHolder(holder: EffectViewHolder, position: Int) {
        val effect = effects[position]
        val isSelected = effect.id == selectedId

        holder.effectImage.setImageResource(effect.imageResId)
        holder.effectImage.clipToOutline = true

        holder.effectIconWrap.setBackgroundResource(
            if (isSelected) R.drawable.bg_effect_selected else R.drawable.bg_effect_unselected
        )

        holder.effectLabel.text = effect.name
        holder.effectLabel.setTextColor(
            if (isSelected) Color.parseColor("#D4A94B") else Color.parseColor("#CCCCCC")
        )

        holder.itemView.setOnClickListener {
            onEffectSelected(effect)
        }
    }

    override fun getItemCount(): Int = effects.size

    class EffectViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val effectIconWrap: FrameLayout = view.findViewById(R.id.effectIconWrap)
        val effectImage: ImageView = view.findViewById(R.id.effectImage)
        val effectLabel: TextView = view.findViewById(R.id.effectLabel)
    }
}

