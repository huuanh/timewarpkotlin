package com.timewarpscan.nativecamera.core.iap

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.timewarpscan.nativecamera.core.ads.AdManager
import com.timewarpscan.nativecamera.core.config.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Google Play Billing wrapper — singleton.
 *
 * Handles:
 *   - BillingClient connection lifecycle
 *   - Product detail queries (in-app + subscriptions)
 *   - Purchase flow initiation
 *   - Purchase acknowledgement
 *   - Purchase restoration
 *   - Premium state persistence via [ConfigManager]
 *
 * Exposes [purchaseState] as a [StateFlow] for UI observation.
 */
object IAPManager {

    private const val TAG = "IAPManager"

    private lateinit var billingClient: BillingClient
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isConnected = false

    // --- Product details cache ---
    private val productDetailsMap = mutableMapOf<String, ProductDetails>()

    // --- Observable state ---
    private val _purchaseState = MutableStateFlow<PurchaseState>(PurchaseState.Idle)
    val purchaseState: StateFlow<PurchaseState> = _purchaseState.asStateFlow()

    // -----------------------------------------------------------------------
    // Purchase state sealed class
    // -----------------------------------------------------------------------

    sealed class PurchaseState {
        data object Idle : PurchaseState()
        data object Connecting : PurchaseState()
        data object Connected : PurchaseState()
        data class ProductsLoaded(val products: List<ProductDetails>) : PurchaseState()
        data object Purchasing : PurchaseState()
        data class PurchaseSuccess(val productId: String) : PurchaseState()
        data class PurchaseFailed(val message: String) : PurchaseState()
        data object Restored : PurchaseState()
        data class Error(val message: String) : PurchaseState()
    }

    // -----------------------------------------------------------------------
    // Initialization
    // -----------------------------------------------------------------------

    /**
     * Initialize billing client and connect.
     * Call from Application.onCreate().
     */
    fun init(context: Context) {
        appContext = context.applicationContext

        billingClient = BillingClient.newBuilder(appContext)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .build()

        connect()
    }

    private fun connect() {
        if (isConnected) return
        _purchaseState.value = PurchaseState.Connecting

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    _purchaseState.value = PurchaseState.Connected
                    Log.d(TAG, "Billing connected")
                    // Restore purchases on connect
                    scope.launch { restorePurchases() }
                } else {
                    _purchaseState.value = PurchaseState.Error("Billing setup failed: ${result.debugMessage}")
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnected = false
                Log.w(TAG, "Billing disconnected")
            }
        })
    }

    /** Ensure connection is alive before operations. */
    private fun ensureConnected(action: () -> Unit) {
        if (isConnected) {
            action()
        } else {
            connect()
            Log.w(TAG, "Reconnecting — action deferred")
        }
    }

    // -----------------------------------------------------------------------
    // Query products
    // -----------------------------------------------------------------------

    /**
     * Query product details for all configured product IDs.
     * Results are cached in [productDetailsMap] and emitted via [purchaseState].
     */
    fun queryProducts() {
        ensureConnected {
            scope.launch {
                val allProducts = mutableListOf<ProductDetails>()

                // In-app products
                if (IAPConfig.IN_APP_PRODUCTS.isNotEmpty()) {
                    val inAppParams = QueryProductDetailsParams.newBuilder()
                        .setProductList(
                            IAPConfig.IN_APP_PRODUCTS.map { id ->
                                QueryProductDetailsParams.Product.newBuilder()
                                    .setProductId(id)
                                    .setProductType(BillingClient.ProductType.INAPP)
                                    .build()
                            }
                        ).build()

                    val (inAppResult, inAppDetails) = billingClient.queryProductDetails(inAppParams)
                    if (inAppResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        inAppDetails?.let { allProducts.addAll(it) }
                    }
                }

                // Subscriptions
                if (IAPConfig.SUBSCRIPTION_PRODUCTS.isNotEmpty()) {
                    val subsParams = QueryProductDetailsParams.newBuilder()
                        .setProductList(
                            IAPConfig.SUBSCRIPTION_PRODUCTS.map { id ->
                                QueryProductDetailsParams.Product.newBuilder()
                                    .setProductId(id)
                                    .setProductType(BillingClient.ProductType.SUBS)
                                    .build()
                            }
                        ).build()

                    val (subsResult, subsDetails) = billingClient.queryProductDetails(subsParams)
                    if (subsResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        subsDetails?.let { allProducts.addAll(it) }
                    }
                }

                // Cache product details
                allProducts.forEach { productDetailsMap[it.productId] = it }
                _purchaseState.value = PurchaseState.ProductsLoaded(allProducts)
                Log.d(TAG, "Loaded ${allProducts.size} products")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Purchase
    // -----------------------------------------------------------------------

    /**
     * Launch the purchase flow for a specific product.
     * @param activity the hosting activity
     * @param productId the product to purchase (must be queried first)
     */
    fun purchase(activity: Activity, productId: String) {
        val productDetails = productDetailsMap[productId]
        if (productDetails == null) {
            _purchaseState.value = PurchaseState.PurchaseFailed("Product not found: $productId")
            return
        }

        _purchaseState.value = PurchaseState.Purchasing

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)

        // For subscriptions, select the first offer
        if (productDetails.productType == BillingClient.ProductType.SUBS) {
            productDetails.subscriptionOfferDetails?.firstOrNull()?.let { offer ->
                productDetailsParams.setOfferToken(offer.offerToken)
            }
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams.build()))
            .build()

        billingClient.launchBillingFlow(activity, flowParams)
    }

    // -----------------------------------------------------------------------
    // Purchase callback
    // -----------------------------------------------------------------------

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase -> handlePurchase(purchase) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _purchaseState.value = PurchaseState.PurchaseFailed("Purchase cancelled")
            }
            else -> {
                _purchaseState.value = PurchaseState.PurchaseFailed(billingResult.debugMessage)
                Log.w(TAG, "Purchase error: ${billingResult.debugMessage}")
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        // Acknowledge if not already
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

            scope.launch {
                val result = billingClient.acknowledgePurchase(params)
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Purchase acknowledged: ${purchase.products}")
                }
            }
        }

        // Check if any purchased product grants premium
        val grantsPremium = purchase.products.any { it in IAPConfig.PREMIUM_PRODUCT_IDS }
        if (grantsPremium) {
            ConfigManager.setPremium(true)
            AdManager.destroyAds()
            _purchaseState.value = PurchaseState.PurchaseSuccess(purchase.products.first())
            Log.d(TAG, "Premium granted via: ${purchase.products}")
        }
    }

    // -----------------------------------------------------------------------
    // Restore
    // -----------------------------------------------------------------------

    /**
     * Restore existing purchases — checks both in-app and subscriptions.
     * Updates premium state if any qualifying purchase is found.
     */
    suspend fun restorePurchases() {
        var hasPremium = false

        // Check in-app purchases
        val inAppParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val inAppResult = billingClient.queryPurchasesAsync(inAppParams)
        if (inAppResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            for (purchase in inAppResult.purchasesList) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    purchase.products.any { it in IAPConfig.PREMIUM_PRODUCT_IDS }
                ) {
                    hasPremium = true
                }
            }
        }

        // Check subscriptions
        val subsParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val subsResult = billingClient.queryPurchasesAsync(subsParams)
        if (subsResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            for (purchase in subsResult.purchasesList) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    purchase.products.any { it in IAPConfig.PREMIUM_PRODUCT_IDS }
                ) {
                    hasPremium = true
                }
            }
        }

        ConfigManager.setPremium(hasPremium)
        if (hasPremium) {
            _purchaseState.value = PurchaseState.Restored
            Log.d(TAG, "Purchases restored — premium active")
        } else {
            Log.d(TAG, "No premium purchases found")
        }
    }

    // -----------------------------------------------------------------------
    // Convenience
    // -----------------------------------------------------------------------

    /** True if the user has an active premium purchase. */
    fun isPremiumUser(): Boolean = ConfigManager.isPremium()

    /** Disconnect billing client. Call from Application teardown if needed. */
    fun disconnect() {
        if (::billingClient.isInitialized) {
            billingClient.endConnection()
            isConnected = false
        }
    }
}
