package com.timewarpscan.nativecamera.ui.settings

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.timewarpscan.nativecamera.R
import com.timewarpscan.nativecamera.databinding.DialogRateBinding

class RateAppDialog : DialogFragment() {

    private var _binding: DialogRateBinding? = null
    private val binding get() = _binding!!

    private var currentRating = 5
    private lateinit var stars: List<ImageView>

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = DialogRateBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        stars = listOf(binding.star1, binding.star2, binding.star3, binding.star4, binding.star5)

        updateStars(currentRating)

        stars.forEachIndexed { index, imageView ->
            imageView.setOnClickListener {
                currentRating = index + 1
                updateStars(currentRating)
            }
        }

        binding.btnCloseRate.setOnClickListener { dismiss() }

        binding.btnRateApp.setOnClickListener {
            if (currentRating < 3) {
                Toast.makeText(requireContext(), "Thank you for your feedback!", Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                openPlayStore()
                dismiss()
            }
        }
    }

    private fun updateStars(rating: Int) {
        stars.forEachIndexed { index, imageView ->
            imageView.setImageResource(
                if (index < rating) R.drawable.ic_star_filled else R.drawable.ic_star_empty
            )
        }
        binding.tvRateHint.text = when (rating) {
            1 -> "Very bad"
            2 -> "Not good"
            3 -> "Quite okay"
            4 -> "Very good"
            5 -> "The best we can get"
            else -> ""
        }
    }

    private fun openPlayStore() {
        val ctx = requireContext()
        val pkg = ctx.packageName
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (_: Exception) {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
