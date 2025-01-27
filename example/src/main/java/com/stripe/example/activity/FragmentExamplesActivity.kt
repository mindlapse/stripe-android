package com.stripe.example.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.stripe.android.*
import com.stripe.android.model.ConfirmPaymentIntentParams
import com.stripe.android.model.Customer
import com.stripe.android.view.ShippingInfoWidget
import com.stripe.example.R
import com.stripe.example.module.RetrofitFactory
import com.stripe.example.service.ExampleEphemeralKeyProvider
import com.stripe.example.service.StripeService
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import okhttp3.ResponseBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.HashMap

class FragmentExamplesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.fragments_example_layout)

        setTitle(R.string.launch_payment_session_from_fragment)

        val newFragment = LauncherFragment()

        val ft = supportFragmentManager.beginTransaction()
        ft.add(R.id.root, newFragment, LauncherFragment::class.java.simpleName)
            .commit()
    }

    class LauncherFragment : Fragment() {

        private val compositeDisposable = CompositeDisposable()

        private lateinit var stripe: Stripe
        private lateinit var paymentSession: PaymentSession
        private lateinit var stripeService: StripeService

        private lateinit var progressBar: ProgressBar
        private lateinit var launchPaymentSessionButton: Button
        private lateinit var launchPaymentAuthButton: Button
        private lateinit var statusTextView: TextView

        override fun onActivityCreated(savedInstanceState: Bundle?) {
            super.onActivityCreated(savedInstanceState)

            stripeService = RetrofitFactory.instance.create(StripeService::class.java)
            stripe = Stripe(requireContext(), PaymentConfiguration.getInstance().publishableKey)
            paymentSession = createPaymentSession(createCustomerSession())

            val rootView = view!!
            statusTextView = rootView.findViewById(R.id.status)
            progressBar = rootView.findViewById(R.id.progress_bar)

            launchPaymentSessionButton = rootView.findViewById(R.id.launch_payment_session)
            launchPaymentSessionButton.setOnClickListener {
                paymentSession.presentPaymentMethodSelection()
            }

            launchPaymentAuthButton = rootView.findViewById(R.id.launch_payment_auth)
            launchPaymentAuthButton.setOnClickListener { createPaymentIntent() }
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            return layoutInflater.inflate(R.layout.launch_payment_session_fragment, null)
        }

        override fun onPause() {
            progressBar.visibility = View.INVISIBLE
            super.onPause()
        }

        override fun onDestroy() {
            compositeDisposable.dispose()
            super.onDestroy()
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)

            progressBar.visibility = View.VISIBLE

            val isPaymentSessionResult =
                paymentSession.handlePaymentData(requestCode, resultCode, data!!)
            if (isPaymentSessionResult) {
                Toast.makeText(
                    requireActivity(),
                    "Received PaymentSession result",
                    Toast.LENGTH_SHORT)
                    .show()
                return
            }

            val isPaymentResult = stripe.onPaymentResult(requestCode, data,
                AuthResultListener(this))
        }

        private fun createPaymentIntent() {
            compositeDisposable.add(
                stripeService.createPaymentIntent(createPaymentIntentParams())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSubscribe {
                        progressBar.visibility = View.VISIBLE
                        launchPaymentAuthButton.isEnabled = false
                        statusTextView.setText(R.string.creating_payment_intent)
                    }
                    .subscribe { handleCreatePaymentIntentResponse(it) })
        }

        private fun onAuthComplete() {
            launchPaymentAuthButton.isEnabled = true
            progressBar.visibility = View.INVISIBLE
        }

        private fun handleCreatePaymentIntentResponse(responseBody: ResponseBody) {
            try {
                val responseData = JSONObject(responseBody.string())
                statusTextView.append("\n\n" + getString(R.string.payment_intent_status,
                    responseData.getString("status")))
                val secret = responseData.getString("secret")
                confirmPaymentIntent(secret)
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }

        private fun createPaymentIntentParams(): HashMap<String, Any> {
            return hashMapOf(
                "payment_method_types[]" to "card",
                "amount" to 1000,
                "currency" to "usd"
            )
        }

        private fun confirmPaymentIntent(paymentIntentClientSecret: String) {
            statusTextView.append("\n\nStarting payment authentication")
            stripe.confirmPayment(this,
                ConfirmPaymentIntentParams.createWithPaymentMethodId(
                    PAYMENT_METHOD_3DS2_REQUIRED,
                    paymentIntentClientSecret,
                    RETURN_URL))
        }

        private fun createPaymentSession(customerSession: CustomerSession): PaymentSession {
            val paymentSession = PaymentSession(this)
            val paymentSessionInitialized = paymentSession.init(
                object : PaymentSession.PaymentSessionListener {
                    override fun onCommunicatingStateChanged(isCommunicating: Boolean) {
                    }

                    override fun onError(errorCode: Int, errorMessage: String) {
                    }

                    override fun onPaymentSessionDataChanged(data: PaymentSessionData) {
                        customerSession.retrieveCurrentCustomer(
                            object : CustomerSession.CustomerRetrievalListener {
                                override fun onCustomerRetrieved(customer: Customer) {
                                    launchPaymentSessionButton.isEnabled = true
                                }

                                override fun onError(
                                    errorCode: Int,
                                    errorMessage: String,
                                    stripeError: StripeError?
                                ) {}
                            })
                    }
                },
                PaymentSessionConfig.Builder()
                    .setHiddenShippingInfoFields(
                        ShippingInfoWidget.CustomizableShippingField.PHONE_FIELD,
                        ShippingInfoWidget.CustomizableShippingField.CITY_FIELD
                    )
                    .build())
            if (paymentSessionInitialized) {
                paymentSession.setCartTotal(2000L)
            }

            return paymentSession
        }

        private fun createCustomerSession(): CustomerSession {
            CustomerSession.initCustomerSession(requireContext(),
                ExampleEphemeralKeyProvider(ProgressListenerImpl(requireActivity())))
            val customerSession = CustomerSession.getInstance()
            customerSession.retrieveCurrentCustomer(
                object : CustomerSession.CustomerRetrievalListener {
                    override fun onCustomerRetrieved(customer: Customer) {}

                    override fun onError(
                        errorCode: Int,
                        errorMessage: String,
                        stripeError: StripeError?
                    ) {}
                }
            )
            return customerSession
        }

        private class ProgressListenerImpl internal constructor(
            activity: Activity
        ) : ExampleEphemeralKeyProvider.ProgressListener {
            private val mActivityRef: WeakReference<Activity> = WeakReference(activity)

            override fun onStringResponse(response: String) {
                val activity = mActivityRef.get()
                if (activity != null && response.startsWith("Error: ")) {
                    Toast.makeText(activity, response, Toast.LENGTH_LONG).show()
                }
            }
        }

        private class AuthResultListener internal constructor(
            fragment: LauncherFragment
        ) : ApiResultCallback<PaymentIntentResult> {
            private val fragmentRef: WeakReference<LauncherFragment> = WeakReference(fragment)

            override fun onSuccess(paymentIntentResult: PaymentIntentResult) {
                val fragment = fragmentRef.get() ?: return

                val paymentIntent = paymentIntentResult.intent
                fragment.statusTextView.append("\n\n" +
                    "Auth status: " + paymentIntentResult.status + "\n\n" +
                    fragment.getString(R.string.payment_intent_status,
                        paymentIntent.status))
                fragment.onAuthComplete()
            }

            override fun onError(e: Exception) {
                val fragment = fragmentRef.get() ?: return

                fragment.statusTextView.append("\n\nException: " + e.message)
                fragment.onAuthComplete()
            }
        }

        companion object {
            private const val PAYMENT_METHOD_3DS2_REQUIRED = "pm_card_threeDSecure2Required"
            private const val RETURN_URL = "stripe://payment_auth"
        }
    }
}
