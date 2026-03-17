package com.timewarpscan.nativecamera.ui.iap

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.timewarpscan.nativecamera.R
import com.timewarpscan.nativecamera.core.iap.IAPConfig
import com.timewarpscan.nativecamera.core.iap.IAPManager
import com.timewarpscan.nativecamera.databinding.ActivityIapBinding
import kotlinx.coroutines.launch

class IAPActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIapBinding

    private enum class Plan { MONTHLY, QUARTERLY, YEARLY }
    private var selectedPlan = Plan.QUARTERLY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupPlans()
        setupPurchaseButton()
        observePurchaseState()

        // Query products on open
        IAPManager.queryProducts()
    }

    private fun setupHeader() {
        binding.btnClose.setOnClickListener { finish() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun setupPlans() {
        updatePlanSelection()

        binding.planMonthly.setOnClickListener {
            selectedPlan = Plan.MONTHLY
            updatePlanSelection()
        }
        binding.planQuarterly.setOnClickListener {
            selectedPlan = Plan.QUARTERLY
            updatePlanSelection()
        }
        binding.planYearly.setOnClickListener {
            selectedPlan = Plan.YEARLY
            updatePlanSelection()
        }
    }

    private fun updatePlanSelection() {
        val plans = mapOf(
            Plan.MONTHLY to binding.planMonthly,
            Plan.QUARTERLY to binding.planQuarterly,
            Plan.YEARLY to binding.planYearly
        )
        plans.forEach { (plan, view) ->
            view.setBackgroundResource(
                if (plan == selectedPlan) R.drawable.bg_iap_plan_selected else R.drawable.bg_iap_plan
            )
        }

        // Update trial info text based on selection
        binding.tvTrialInfo.text = when (selectedPlan) {
            Plan.MONTHLY -> "3 Days for free, then\n\$4.99/week"
            Plan.QUARTERLY -> "3 Days for free, then\n\$9.99/quarter"
            Plan.YEARLY -> "3 Days for free, then\n\$29.99/year"
        }
    }

    private fun setupPurchaseButton() {
        binding.btnPurchase.setOnClickListener {
            val productId = when (selectedPlan) {
                Plan.MONTHLY -> IAPConfig.SUB_MONTHLY
                Plan.QUARTERLY -> IAPConfig.SUB_QUARTERLY
                Plan.YEARLY -> IAPConfig.SUB_YEARLY
            }
            IAPManager.purchase(this, productId)
        }
    }

    private fun observePurchaseState() {
        lifecycleScope.launch {
            IAPManager.purchaseState.collect { state ->
                when (state) {
                    is IAPManager.PurchaseState.PurchaseSuccess -> {
                        Toast.makeText(this@IAPActivity, "Purchase successful! 🎉", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    is IAPManager.PurchaseState.PurchaseFailed -> {
                        Toast.makeText(this@IAPActivity, state.message, Toast.LENGTH_SHORT).show()
                    }
                    is IAPManager.PurchaseState.Restored -> {
                        if (IAPManager.isPremiumUser()) {
                            Toast.makeText(this@IAPActivity, "Premium restored!", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
