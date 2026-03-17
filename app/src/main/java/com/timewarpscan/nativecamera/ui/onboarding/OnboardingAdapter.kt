package com.timewarpscan.nativecamera.ui.onboarding

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.timewarpscan.nativecamera.databinding.ItemOnboardingPageBinding

data class OnboardingPage(
    val imageRes: Int,
    val title: String,
    val subtitle: String
)

class OnboardingAdapter(
    private val pages: List<OnboardingPage>
) : RecyclerView.Adapter<OnboardingAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemOnboardingPageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOnboardingPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val page = pages[position]
        holder.binding.imgOnboarding.setImageResource(page.imageRes)
        holder.binding.tvTitle.text = page.title
        holder.binding.tvSubtitle.text = page.subtitle
    }

    override fun getItemCount() = pages.size
}
