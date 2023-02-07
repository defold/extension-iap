package com.defold.iap;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import android.app.Activity;
import android.util.Log;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClient.ProductType;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.Purchase.PurchaseState;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetails.OneTimePurchaseOfferDetails;
import com.android.billingclient.api.ProductDetails.PricingPhases;
import com.android.billingclient.api.ProductDetails.PricingPhase;
import com.android.billingclient.api.ProductDetails.RecurrenceMode;
import com.android.billingclient.api.ProductDetails.SubscriptionOfferDetails;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsParams.Product;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;

public class IapGooglePlay implements PurchasesUpdatedListener {
    public static final String TAG = "IapGooglePlay";

    private Map<String, ProductDetails> products = new HashMap<String, ProductDetails>();
    private BillingClient billingClient;
    private IPurchaseListener purchaseListener;
    private boolean autoFinishTransactions;
    private Activity activity;

    public IapGooglePlay(Activity activity, boolean autoFinishTransactions) {
        this.activity = activity;
        this.autoFinishTransactions = autoFinishTransactions;

        billingClient = BillingClient.newBuilder(activity).setListener(this).enablePendingPurchases().build();
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                    Log.v(TAG, "Setup finished");
                    // NOTE: we will not query purchases here. This is done
                    // when the extension listener is set
                }
                else {
                    Log.wtf(TAG, "Setup error: " + billingResult.getDebugMessage());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.v(TAG, "Service disconnected");
            }
        });
    }

    public void stop() {
        Log.d(TAG, "stop()");
        if (billingClient.isReady()) {
            billingClient.endConnection();
        }
    }

    private String toISO8601(final Date date) {
        String formatted = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(date);
        return formatted.substring(0, 22) + ":" + formatted.substring(22);
    }

    private String convertPurchase(Purchase purchase) {
        // original JSON:
        // {
        // 	"orderId":"GPA.3301-1670-7033-37542",
        // 	"packageName":"com.defold.extension.iap",
        // 	"productId":"com.defold.iap.goldbar.medium",
        // 	"purchaseTime":1595455967875,
        // 	"purchaseState":0,
        // 	"purchaseToken":"kacckamkehbbammphdcnhbme.AO-J1OxznnK6E8ILqaAgrPa-3sfaHny424R1e_ZJ2LkaJVsy-5aEOmHizw0vUp-017m8OUvw1rSvfAHbOog1fIvDGJmjaze3MEVFOh1ayJsNFfPDUGwMA_u_9rlV7OqX_nnIyDShH2KE5WrnMC0yQyw7sg5hfgeW6A",
        // 	"acknowledged":false
        // }
        Log.d(TAG, "convertPurchase() original json: " + purchase.getOriginalJson());
        JSONObject p = new JSONObject();
        try {
            JSONObject original = new JSONObject(purchase.getOriginalJson());
            p.put("ident", original.get("productId"));
            p.put("state", purchaseStateToDefoldState(purchase.getPurchaseState()));
            p.put("trans_ident", purchase.getOrderId());
            p.put("date", toISO8601(new Date(purchase.getPurchaseTime())));
            p.put("receipt", purchase.getPurchaseToken());
            p.put("signature", purchase.getSignature());
            p.put("original_json", purchase.getOriginalJson());
        }
        catch (JSONException e) {
            Log.wtf(TAG, "Failed to convert purchase", e);
        }
        return p.toString();
    }

    private JSONArray convertSubscriptionOfferPricingPhases(SubscriptionOfferDetails details) {
        JSONArray a = new JSONArray();
        try {
            List<PricingPhase> pricingPhases = details.getPricingPhases().getPricingPhaseList();
            for (PricingPhase pricingPhase : pricingPhases) {
                JSONObject o = new JSONObject();
                o.put("price_string", pricingPhase.getFormattedPrice());
                o.put("price", pricingPhase.getPriceAmountMicros() * 0.000001);
                o.put("currency_code", pricingPhase.getPriceCurrencyCode());
                o.put("billing_period", pricingPhase.getBillingPeriod());
                o.put("billing_cycle_count", pricingPhase.getBillingCycleCount());
                switch (pricingPhase.getRecurrenceMode()) {
                case RecurrenceMode.FINITE_RECURRING:
                    o.put("recurrence_mode", "FINITE");
                    break;
                case RecurrenceMode.INFINITE_RECURRING:
                    o.put("recurrence_mode", "INFINITE");
                    break;
                default:
                case RecurrenceMode.NON_RECURRING:
                    o.put("recurrence_mode", "NONE");
                    break;
                }
                a.put(o);
            }
        }
        catch(JSONException e) {
            Log.wtf(TAG, "Failed to convert subscription offer pricing phases", e);
        }
        return a;
    }

    private JSONObject convertProductDetails(ProductDetails productDetails) {
        JSONObject p = new JSONObject();
        try {
            p.put("ident", productDetails.getProductId());
            p.put("title", productDetails.getTitle());
            p.put("description", productDetails.getDescription());
            if (productDetails.getProductType().equals(ProductType.INAPP)) {
                OneTimePurchaseOfferDetails offerDetails = productDetails.getOneTimePurchaseOfferDetails();
                p.put("price_string", offerDetails.getFormattedPrice());
                p.put("currency_code", offerDetails.getPriceCurrencyCode());
                p.put("price", offerDetails.getPriceAmountMicros() * 0.000001);
            }
            else if (productDetails.getProductType().equals(ProductType.SUBS)) {
                List<SubscriptionOfferDetails> subscriptionOfferDetails = productDetails.getSubscriptionOfferDetails();
                JSONArray a = new JSONArray();
                for (SubscriptionOfferDetails offerDetails : subscriptionOfferDetails) {
                    JSONObject o = new JSONObject();
                    o.put("token", offerDetails.getOfferToken());
                    o.put("pricing", convertSubscriptionOfferPricingPhases(offerDetails));
                    a.put(o);
                }
                p.put("subscriptions", a);
            }
            else {
                Log.i(TAG, "convertProductDetails() unknown product type " + productDetails.getProductType());
            }
        }
        catch(JSONException e) {
            Log.wtf(TAG, "Failed to convert product details", e);
        }
        return p;
    }

    private int purchaseStateToDefoldState(int purchaseState) {
        int defoldState;
        switch(purchaseState) {
            case PurchaseState.PENDING:
                defoldState = IapJNI.TRANS_STATE_PURCHASING;
                break;
            case PurchaseState.PURCHASED:
                defoldState = IapJNI.TRANS_STATE_PURCHASED;
                break;
            default:
            case PurchaseState.UNSPECIFIED_STATE:
                defoldState = IapJNI.TRANS_STATE_UNVERIFIED;
                break;
        }
        return defoldState;
    }

    private int billingResponseCodeToDefoldResponse(int responseCode) {
        int defoldResponse;
        switch(responseCode) {
            case BillingResponseCode.BILLING_UNAVAILABLE:
                defoldResponse = IapJNI.BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE;
                break;
            case BillingResponseCode.DEVELOPER_ERROR:
                defoldResponse = IapJNI.BILLING_RESPONSE_RESULT_DEVELOPER_ERROR;
                break;
            case BillingResponseCode.ITEM_ALREADY_OWNED:
                defoldResponse = IapJNI.BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED;
                break;
            case BillingResponseCode.ITEM_NOT_OWNED:
                defoldResponse = IapJNI.BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED;
                break;
            case BillingResponseCode.ITEM_UNAVAILABLE:
                defoldResponse = IapJNI.BILLING_RESPONSE_RESULT_ITEM_UNAVAILABLE;
                break;
            case BillingResponseCode.OK:
                defoldResponse = IapJNI.BILLING_RESPONSE_RESULT_OK;
                break;
            case BillingResponseCode.SERVICE_TIMEOUT:
            case BillingResponseCode.SERVICE_UNAVAILABLE:
            case BillingResponseCode.SERVICE_DISCONNECTED:
                defoldResponse = IapJNI.BILLING_RESPONSE_RESULT_SERVICE_UNAVAILABLE;
                break;
            case BillingResponseCode.USER_CANCELED:
                defoldResponse = IapJNI.BILLING_RESPONSE_RESULT_USER_CANCELED;
                break;
            case BillingResponseCode.FEATURE_NOT_SUPPORTED:
            case BillingResponseCode.ERROR:
            default:
                defoldResponse = IapJNI.BILLING_RESPONSE_RESULT_ERROR;
                break;
        }
        Log.d(TAG, "billingResponseCodeToDefoldResponse: " + responseCode + " defoldResponse: " + defoldResponse);
        return defoldResponse;
    }

    private int billingResultToDefoldResponse(BillingResult result) {
        return billingResponseCodeToDefoldResponse(result.getResponseCode());
    }

    private void invokeOnPurchaseResultListener(IPurchaseListener purchaseListener, int billingResultCode, String purchaseData) {
        if (purchaseListener == null) {
            Log.w(TAG, "Received billing result but no listener has been set");
            return;
        }
        purchaseListener.onPurchaseResult(billingResultCode, purchaseData);
    }
    private void invokeOnPurchaseResultListener(IPurchaseListener purchaseListener, BillingResult billingResult, Purchase purchase) {
        invokeOnPurchaseResultListener(purchaseListener, billingResultToDefoldResponse(billingResult), convertPurchase(purchase));
    }
    private void invokeOnPurchaseResultListener(IPurchaseListener purchaseListener, BillingResult billingResult) {
        invokeOnPurchaseResultListener(purchaseListener, billingResultToDefoldResponse(billingResult), "");
    }

    /**
     * This method is called either explicitly from Lua or from extension code
     * when "set_listener()" is called from Lua.
     * The method will query purchases and try to handle them one by one (either
     * trying to consume/finish them or simply notify the provided listener).
     */
    public void processPendingConsumables(final IPurchaseListener purchaseListener) {
        Log.d(TAG, "processPendingConsumables()");


        PurchasesResponseListener purchasesListener = new PurchasesResponseListener() {
            private List<Purchase> allPurchases = new ArrayList<Purchase>();
            private int queries = 2;

            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> purchases) {
                if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                    Log.e(TAG, "Unable to query pending purchases: " + billingResult.getDebugMessage());
                }
                if (purchases != null) {
                    allPurchases.addAll(purchases);
                }
                // we're finished when we have queried for both in-app and subs
                queries--;
                if (queries == 0) {
                    for (Purchase purchase : allPurchases) {
                        handlePurchase(purchase, purchaseListener);
                    }
                }
            }
        };

        final QueryPurchasesParams inappParams = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build();
        final QueryPurchasesParams subsParams = QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build();
        billingClient.queryPurchasesAsync(inappParams, purchasesListener);
        billingClient.queryPurchasesAsync(subsParams, purchasesListener);
    }

    /**
     * Consume a purchase. This will acknowledge the purchase and make it
     * available to buy again.
     */
    private void consumePurchase(final String purchaseToken, final ConsumeResponseListener consumeListener) {
        Log.d(TAG, "consumePurchase() " + purchaseToken);
        final ConsumeParams consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build();

        billingClient.consumeAsync(consumeParams, consumeListener);
    }

    /**
     * Called from Lua. This method will try to consume a purchase.
     */
    public void finishTransaction(final String purchaseToken, final IPurchaseListener purchaseListener) {
        Log.d(TAG, "finishTransaction() " + purchaseToken);
        consumePurchase(purchaseToken, new ConsumeResponseListener() {
            @Override
            public void onConsumeResponse(BillingResult billingResult, String purchaseToken) {
                Log.d(TAG, "finishTransaction() response code " + billingResult.getResponseCode() + " purchaseToken: " + purchaseToken);
                // note: we only call the purchase listener if an error happens
                if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                    Log.e(TAG, "Unable to consume purchase: " + billingResult.getDebugMessage());
                    invokeOnPurchaseResultListener(purchaseListener, billingResult);
                }
            }
        });
    }

    /**
     * Called from Lua. This method will try to acknowledge a purchase (but not finish/consume it).
     */
    public void acknowledgeTransaction(final String purchaseToken, final IPurchaseListener purchaseListener) {
        Log.d(TAG, "acknowledgeTransaction() " + purchaseToken);

        AcknowledgePurchaseParams acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build();

        billingClient.acknowledgePurchase(acknowledgeParams, new AcknowledgePurchaseResponseListener() {
            @Override
            public void onAcknowledgePurchaseResponse(BillingResult billingResult) {
                Log.d(TAG, "acknowledgeTransaction() response code " + billingResult.getResponseCode());
                // note: we only call the purchase listener if an error happens
                if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                    Log.e(TAG, "Unable to acknowledge purchase: " + billingResult.getDebugMessage());
                    invokeOnPurchaseResultListener(purchaseListener, billingResult);
                }
            }
        });
    }

    /**
     * Handle a purchase. If the extension is configured to automatically
     * finish transactions the purchase will be immediately consumed. Otherwise
     * the product will be returned via the listener without being consumed.
     * NOTE: Billing 3.0+ requires purchases to be acknowledged within 3 days of
     * purchase unless they are consumed.
     */
    private void handlePurchase(final Purchase purchase, final IPurchaseListener purchaseListener) {
        if (this.autoFinishTransactions) {
            consumePurchase(purchase.getPurchaseToken(), new ConsumeResponseListener() {
                @Override
                public void onConsumeResponse(BillingResult billingResult, String purchaseToken) {
                    Log.d(TAG, "handlePurchase() response code " + billingResult.getResponseCode() + " purchaseToken: " + purchaseToken);
                    invokeOnPurchaseResultListener(purchaseListener, billingResult, purchase);
                }
            });
        }
        else {
            invokeOnPurchaseResultListener(purchaseListener, billingResponseCodeToDefoldResponse(BillingResponseCode.OK), convertPurchase(purchase));
        }
    }

    /**
     * BillingClient listener set in the constructor.
     */
    @Override
    public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases) {
        if (billingResult.getResponseCode() == BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase, this.purchaseListener);
            }
        }
        else {
            invokeOnPurchaseResultListener(this.purchaseListener, billingResult);
        }
    }

    /**
     * Buy a product. This method stores the listener and uses it in the
     * onPurchasesUpdated() callback.
     */
    private void buyProduct(ProductDetails pd, final String token, final IPurchaseListener purchaseListener) {
        this.purchaseListener = purchaseListener;

        List<ProductDetailsParams> productDetailsParams = new ArrayList();
        if (pd.getProductType().equals(ProductType.SUBS)) {
            productDetailsParams.add(ProductDetailsParams.newBuilder().setProductDetails(pd).setOfferToken(token).build());
        }
        else {
            productDetailsParams.add(ProductDetailsParams.newBuilder().setProductDetails(pd).build());
        }

        final BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(productDetailsParams).build();

        BillingResult billingResult = billingClient.launchBillingFlow(this.activity, billingFlowParams);
        if (billingResult.getResponseCode() != BillingResponseCode.OK) {
            Log.e(TAG, "Purchase failed: " + billingResult.getDebugMessage());
            invokeOnPurchaseResultListener(purchaseListener, billingResult);
        }
    }

    /**
     * Called from Lua.
     */
    public void buy(final String product, final String token, final IPurchaseListener purchaseListener) {
        Log.d(TAG, "buy()");

        ProductDetails pd = this.products.get(product);
        if (pd != null) {
            buyProduct(pd, token, purchaseListener);
        }
        else {
            List<String> productList = new ArrayList<String>();
            productList.add(product);
            queryProductDetailsAsync(productList, new ProductDetailsResponseListener() {
                @Override
                public void onProductDetailsResponse(BillingResult billingResult, List<ProductDetails> productDetailsList) {
                    if (billingResult.getResponseCode() == BillingResponseCode.OK && (productDetailsList != null) && !productDetailsList.isEmpty()) {
                        buyProduct(productDetailsList.get(0), token, purchaseListener);
                    }
                    else {
                        Log.e(TAG, "Unable to get product details before buying: " + billingResult.getDebugMessage());
                        invokeOnPurchaseResultListener(purchaseListener, billingResult);
                    }
                }
            });
        }
    }

    /**
     * Get details for a list of products. The products can be a mix of
     * in-app products and subscriptions.
     */
    private void queryProductDetailsAsync(final List<String> productList, final ProductDetailsResponseListener listener) {
        ProductDetailsResponseListener detailsListener = new ProductDetailsResponseListener() {
            private List<ProductDetails> allProductDetails = new ArrayList<ProductDetails>();
            private int queries = 2;

            @Override
            public void onProductDetailsResponse(BillingResult billingResult, List<ProductDetails> productDetails) {
                if (productDetails != null) {
                    // cache products (cache will be used to speed up buying)
                    for (ProductDetails pd : productDetails) {
                        IapGooglePlay.this.products.put(pd.getProductId(), pd);
                    }
                    // add to list of all product details
                    allProductDetails.addAll(productDetails);
                }
                // we're finished when we have queried for both in-app and subs
                queries--;
                if (queries == 0) {
                    listener.onProductDetailsResponse(billingResult, allProductDetails);
                }
            }
        };

        // we don't know if a product is a subscription or inapp product type
        // instread we create two product lists from the same set of products and use INAPP for one and SUBS for the other
        List<Product> inappProductList = new ArrayList();
        List<Product> subsProductList = new ArrayList();
        for (String productId : productList) {
            inappProductList.add(Product.newBuilder().setProductId(productId).setProductType(ProductType.INAPP).build());
            subsProductList.add(Product.newBuilder().setProductId(productId).setProductType(ProductType.SUBS).build());
        }

        // do one query per product type
        final QueryProductDetailsParams inappParams = QueryProductDetailsParams.newBuilder().setProductList(inappProductList).build();
        final QueryProductDetailsParams subsParams = QueryProductDetailsParams.newBuilder().setProductList(subsProductList).build();
        billingClient.queryProductDetailsAsync(inappParams, detailsListener);
        billingClient.queryProductDetailsAsync(subsParams, detailsListener);
    }

    /**
     * Called from Lua.
     */
    public void listItems(final String products, final IListProductsListener productsListener, final long commandPtr) {
        Log.d(TAG, "listItems()");

        // create list of product ids from comma separated string
        List<String> productList = new ArrayList<String>();
        for (String p : products.split(",")) {
            if (p.trim().length() > 0) {
                productList.add(p);
            }
        }

        queryProductDetailsAsync(productList, new ProductDetailsResponseListener() {
            @Override
            public void onProductDetailsResponse(BillingResult billingResult, List<ProductDetails> productDetails) {
                JSONArray a = new JSONArray();
                if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                    for (ProductDetails pd : productDetails) {
                        a.put(convertProductDetails(pd));
                    }
                }
                else {
                    Log.e(TAG, "Unable to list products: " + billingResult.getDebugMessage());
                }
                productsListener.onProductsResult(billingResultToDefoldResponse(billingResult), a.toString(), commandPtr);
            }
        });
    }

    /**
     * Called from Lua.
     */
    public void restore(final IPurchaseListener listener) {
        Log.d(TAG, "restore()");
        processPendingConsumables(listener);
    }
}
