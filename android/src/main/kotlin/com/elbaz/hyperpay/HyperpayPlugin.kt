package com.elbaz.hyperpay

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.oppwa.mobile.connect.exception.PaymentError
import com.oppwa.mobile.connect.exception.PaymentException
import com.oppwa.mobile.connect.payment.PaymentParams
import com.oppwa.mobile.connect.payment.card.CardPaymentParams
import com.oppwa.mobile.connect.provider.*
import com.oppwa.mobile.connect.threeds.OppThreeDSConfig
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result


/** HyperpayPlugin */
class HyperpayPlugin : FlutterPlugin, MethodCallHandler, ITransactionListener, ActivityAware,
    ThreeDSWorkflowListener {
    private val TAG = "HyperpayPlugin"
    private val CUSTOM_TAB_PACKAGE_NAME = "com.android.chrome"

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private var channelResult: MethodChannel.Result? = null

    private var mActivity: Activity? = null

    private var paymentProvider: OppPaymentProvider? = null
    private var intent: Intent? = null

    // Get the checkout ID from the endpoint on your server
    private var checkoutID = ""

    private var paymentMode = ""

    // Card details
    private var brand = Brand.UNKNOWN
    private var cardHolder: String = ""
    private var cardNumber: String = ""
    private var expiryMonth: String = ""
    private var expiryYear: String = ""
    private var cvv: String = ""
    private var STCPAY = ""
    private var shopperResultUrl: String = ""

    private var mCustomTabsClient: CustomTabsClient? = null;
    private var mCustomTabsIntent: CustomTabsIntent? = null;
    private var hiddenLifecycleReference: HiddenLifecycleReference? = null;

    // used to store the result URL from ChromeCustomTabs intent
    private var redirectData = ""
    val threeDSWorkflowListener: ThreeDSWorkflowListener = object : ThreeDSWorkflowListener {
        override fun onThreeDSChallengeRequired(): Activity? {
            // provide your Activity
            return mActivity
        }

        override fun onThreeDSConfigRequired(): OppThreeDSConfig {
            // provide your OppThreeDSConfig


            return super.onThreeDSConfigRequired()
        }
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if(event == Lifecycle.Event.ON_RESUME && (redirectData.isEmpty() && mCustomTabsIntent != null)) {
            Log.d(TAG, "Cancelling.")
            mCustomTabsIntent = null
            success("canceled")
        }
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "plugins.mohamedElbaz/hyperpay")
        channel.setMethodCallHandler(this)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        mActivity = binding.activity;
        hiddenLifecycleReference = (binding.lifecycle as HiddenLifecycleReference)
        hiddenLifecycleReference?.lifecycle?.addObserver(lifecycleObserver)

        // Remove any underscores from the application ID for Uri parsing
        // NOTE: It's important to add your application ID as the scheme, followed by ".payments"
        // without any underscores.
        shopperResultUrl = mActivity!!.packageName.replace("_", "")
        shopperResultUrl += ".payments"

        binding.addOnNewIntentListener {
            if (it.scheme?.equals(shopperResultUrl, ignoreCase = true) == true) {
                redirectData = it.scheme.toString()

                Log.d(TAG, "Success, redirecting to app...")
                success("success")
            }
            true
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        hiddenLifecycleReference?.lifecycle?.removeObserver(lifecycleObserver)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        hiddenLifecycleReference = (binding.lifecycle as HiddenLifecycleReference)
        hiddenLifecycleReference?.lifecycle?.addObserver(lifecycleObserver)
    }

    override fun onDetachedFromActivity() {
        if (intent != null) {
            mActivity!!.stopService(intent)
        }

        hiddenLifecycleReference?.lifecycle?.removeObserver(lifecycleObserver)
        hiddenLifecycleReference = null
        mActivity = null
    }

    // Handling result options
    private val handler: Handler = Handler(Looper.getMainLooper())

    private fun success(result: Any?) {
        handler.post { channelResult!!.success(result) }
    }

    private fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
        handler.post { channelResult!!.error(errorCode, errorMessage, errorDetails) }
    }

    private var cctConnection: CustomTabsServiceConnection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            mCustomTabsClient = client
            mCustomTabsClient?.warmup(0L)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mCustomTabsClient = null
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "setup_service" -> {
                try {
                    val args: Map<String, Any> = call.arguments as Map<String, Any>

                    paymentMode = args["mode"] as String
                    var providerMode = Connect.ProviderMode.TEST;

                    if(paymentMode == "LIVE") {
                        providerMode =  Connect.ProviderMode.LIVE;
                    }

                    paymentProvider = OppPaymentProvider(mActivity!!.application, providerMode);
                    // a listener for 3DS
                    paymentProvider!!.setThreeDSWorkflowListener{mActivity}

                    // Bind CustomTabs service with the current app activity
                    CustomTabsClient.bindCustomTabsService(mActivity!!, CUSTOM_TAB_PACKAGE_NAME, cctConnection);

                    Log.d(TAG, "Payment mode is set to $paymentMode")
                    result.success(null)
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()

                    result.error("", e.message, e.stackTrace)
                }
            }

            "start_payment_transaction" -> {
                channelResult = result

                val args: Map<String, Any> = call.arguments as Map<String, Any>
                checkoutID = (args["checkoutID"] as String?)!!
                brand = Brand.valueOf(args["brand"].toString())

                val card: Map<String, Any> = args["card"] as Map<String, Any>
                cardHolder = (card["holder"] as String?)!!
                cardNumber = (card["number"] as String?)!!
                expiryMonth = (card["expiryMonth"] as String?)!!
                expiryYear = (card["expiryYear"] as String?)!!
                STCPAY = (card["STC_PAY"] as String?)!!
                cvv = (card["cvv"] as String?)!!

                when (brand) {
                    // If the brand is not provided it returns an error result
                    Brand.UNKNOWN -> result.error(
                            "0.1",
                            "Please provide a valid brand",
                            ""
                    )
                    Brand.STCPAY -> {
                        Log.d("STC", "STC Start")
                        val paymentParams = PaymentParams(checkoutID, "STC_PAY")
                        try {
                            paymentParams.shopperResultUrl = "$shopperResultUrl://result"
                            val transaction = Transaction(paymentParams)
                            paymentProvider!!.setThreeDSWorkflowListener(threeDSWorkflowListener)
                            paymentProvider!!.submitTransaction(transaction, this)
                            Log.d("STC", "STC END")

                        }
                        catch (e:PaymentException) {
                            Log.d("STC", "STC faild")
                            result.error(
                                "0.2",
                                e.localizedMessage,
                                e.error
                            )
                        }

                    }

                    else -> {
                        checkCreditCardValid(result)

                        val paymentParams: PaymentParams = CardPaymentParams(
                                checkoutID,
                                brand.name,
                                cardNumber,
                                cardHolder,
                                expiryMonth,
                                expiryYear,
                                cvv
                        )

                        //Set shopper result URL
                        paymentParams.shopperResultUrl = "$shopperResultUrl://result"

                        try {
                            val transaction = Transaction(paymentParams)
                            paymentProvider?.submitTransaction(transaction, this)
                        } catch (e: PaymentException) {
                            result.error(
                                    "0.2",
                                    e.localizedMessage,
                                    ""
                            )
                        }
                    }
                }
            }
            else -> {
                result.notImplemented()
            }
        }
    }


    /**
     * This function checks the provided card params and return
     * a PlatformException to Flutter if any are not valid.
     * */
    private fun checkCreditCardValid(result: Result) {
        if (!CardPaymentParams.isNumberValid(cardNumber)) {
            result.error(
                    "1.1",
                    "Card number is not valid for brand ${brand.name}",
                    ""
            )
        } else if (!CardPaymentParams.isHolderValid(cardHolder)) {
            result.error(
                    "1.2",
                    "Holder name is not valid",
                    ""
            )
        } else if (!CardPaymentParams.isExpiryMonthValid(expiryMonth)) {
            result.error(
                    "1.3",
                    "Expiry month is not valid",
                    ""
            )
        } else if (!CardPaymentParams.isExpiryYearValid(expiryYear)) {
            result.error(
                    "1.4",
                    "Expiry year is not valid",
                    ""
            )
        } else if (!CardPaymentParams.isCvvValid(cvv)) {
            result.error(
                    "1.5",
                    "CVV is not valid",
                    ""
            )
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun transactionCompleted(transaction: Transaction) {
        try {
            if (transaction.transactionType == TransactionType.SYNC) {
                // Send request to your server to obtain transaction status
                success("Transaction completed as synchronous.")
            } else {
                val uri = Uri.parse(transaction.redirectUrl)
                redirectData = ""

                val session = mCustomTabsClient?.newSession(object : CustomTabsCallback() {
                    override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
                        Log.w(TAG, "onNavigationEvent: Code = $navigationEvent")
                        when (navigationEvent) {
                            TAB_HIDDEN -> {
                                if (redirectData.isEmpty()) {
                                    mCustomTabsIntent = null
                                    success("canceled")
                                }
                            }
                        }
                    }
                })

                val builder = CustomTabsIntent.Builder(session)
                mCustomTabsIntent = builder.build()
                mActivity?.intent?.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                mCustomTabsIntent?.intent?.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                mCustomTabsIntent?.launchUrl(mActivity!!, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()

            // Display error
            error("${e.message}️")
        }
    }

    override fun transactionFailed(transaction: Transaction, error: PaymentError) {
        error(
                "${error.errorCode}",
                error.errorMessage,
                "${error.errorInfo}"
        )
    }

    override fun onThreeDSChallengeRequired(): Activity? {
        return  mActivity;
    }


}
