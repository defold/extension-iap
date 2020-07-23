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
import com.android.billingclient.api.BillingClient.SkuType;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.Purchase.PurchasesResult;
import com.android.billingclient.api.Purchase.PurchaseState;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.SkuDetailsResponseListener;


public class IapGooglePlay implements PurchasesUpdatedListener {
    public static final String TAG = "IapGooglePlay";

    private Map<String, SkuDetails> products = new HashMap<String, SkuDetails>();
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
        billingClient.endConnection();
    }

    public String toISO8601(final Date date) {
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
        }
        catch (JSONException e) {
            Log.wtf(TAG, "Failed to convert purchase", e);
        }
        return p.toString();
    }

    private JSONObject convertSkuDetails(SkuDetails skuDetails) {
        JSONObject p = new JSONObject();
        try {
            p.put("price_string", skuDetails.getPrice());
            p.put("ident", skuDetails.getSku());
            p.put("currency_code", skuDetails.getPriceCurrencyCode());
            p.put("price", skuDetails.getPriceAmountMicros() * 0.000001);
        }
        catch(JSONException e) {
            Log.wtf(TAG, "Failed to convert sku details", e);
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
        return defoldResponse;
    }

    private int billingResultToDefoldResponse(BillingResult result) {
        return billingResponseCodeToDefoldResponse(result.getResponseCode());
    }

    /**
     * Query Google Play for purchases done within the app.
     */
    private List<Purchase> queryPurchases(final String type) {
        PurchasesResult result = billingClient.queryPurchases(type);
        if (result.getBillingResult().getResponseCode() != BillingResponseCode.OK) {
            Log.e(TAG, "Unable to query pending purchases: " + result.getBillingResult().getDebugMessage());
            return new ArrayList<Purchase>();
        }
        return result.getPurchasesList();
    }

    /**
     * This method is called either explicitly from Lua or from extension code
     * when "set_listener()" is called from Lua.
     * The method will query purchases and try to handle them one by one (either
     * trying to consume/finish them or simply notify the provided listener).
     */
    public void processPendingConsumables(final IPurchaseListener purchaseListener) {
        Log.d(TAG, "processPendingConsumables()");
        List<Purchase> purchasesList = new ArrayList<Purchase>();
        purchasesList.addAll(queryPurchases(SkuType.INAPP));
        purchasesList.addAll(queryPurchases(SkuType.SUBS));
        for (Purchase purchase : purchasesList) {
            handlePurchase(purchase, purchaseListener);
        }
    }

    /**
     * Consume a purchase. This will acknowledge the purchase and make it
     * available to buy again.
     */
    private void consumePurchase(final String purchaseToken, final ConsumeResponseListener consumeListener) {
        Log.d(TAG, "consumePurchase() " + purchaseToken);
        ConsumeParams consumeParams = ConsumeParams.newBuilder()
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
                    purchaseListener.onPurchaseResult(billingResultToDefoldResponse(billingResult), "");
                }
            }
        });
    }

    /**
     * Handle a purchase. If the extension is configured to automatically
     * finish transactions the purchase will be immediately consumed. Otherwise
     * the product will be returned via the listener without being consumed.
     * NOTE: Billing 3.0 requires purchases to be acknowledged within 3 days of
     * purchase unless they are consumed.
     */
    private void handlePurchase(final Purchase purchase, final IPurchaseListener purchaseListener) {
        if (this.autoFinishTransactions) {
            consumePurchase(purchase.getPurchaseToken(), new ConsumeResponseListener() {
                @Override
                public void onConsumeResponse(BillingResult billingResult, String purchaseToken) {
                    Log.d(TAG, "handlePurchase() response code " + billingResult.getResponseCode() + " purchaseToken: " + purchaseToken);
                    purchaseListener.onPurchaseResult(billingResultToDefoldResponse(billingResult), convertPurchase(purchase));
                }
            });
        }
        else {
            purchaseListener.onPurchaseResult(billingResponseCodeToDefoldResponse(BillingResponseCode.OK), convertPurchase(purchase));
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
            this.purchaseListener.onPurchaseResult(billingResultToDefoldResponse(billingResult), "");
        }
    }

    /**
     * Buy a product. This method stores the listener and uses it in the
     * onPurchasesUpdated() callback.
     */
    private void buyProduct(SkuDetails sku, final IPurchaseListener purchaseListener) {
        this.purchaseListener = purchaseListener;

        BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
            .setSkuDetails(sku)
            .build();

        BillingResult billingResult = billingClient.launchBillingFlow(this.activity, billingFlowParams);
        if (billingResult.getResponseCode() != BillingResponseCode.OK) {
            Log.e(TAG, "Purchase failed: " + billingResult.getDebugMessage());
            purchaseListener.onPurchaseResult(billingResultToDefoldResponse(billingResult), "");
        }
    }

    /**
     * Called from Lua.
     */
    public void buy(final String product, final IPurchaseListener purchaseListener) {
        Log.d(TAG, "buy()");

        SkuDetails sku = this.products.get(product);
        if (sku != null) {
            buyProduct(sku, purchaseListener);
        }
        else {
            List<String> skuList = new ArrayList<String>();
            skuList.add(product);
            querySkuDetailsAsync(skuList, new SkuDetailsResponseListener() {
                @Override
                public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> skuDetailsList) {
                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                        buyProduct(skuDetailsList.get(0), purchaseListener);
                    }
                    else {
                        Log.e(TAG, "Unable to get product details before buying: " + billingResult.getDebugMessage());
                        purchaseListener.onPurchaseResult(billingResultToDefoldResponse(billingResult), "");
                    }
                }
            });
        }
    }

    /**
     * Get details for a list of products. The products can be a mix of
     * in-app products and subscriptions.
     */
    private void querySkuDetailsAsync(final List<String> skuList, final SkuDetailsResponseListener listener) {
        SkuDetailsResponseListener detailsListener = new SkuDetailsResponseListener() {
            private List<SkuDetails> allSkuDetails = new ArrayList<SkuDetails>();
            private int queries = 2;

            @Override
            public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> skuDetails) {
                // cache skus (cache will be used to speed up buying)
                for (SkuDetails sd : skuDetails) {
                    IapGooglePlay.this.products.put(sd.getSku(), sd);
                }
                // add to list of all sku details
                allSkuDetails.addAll(skuDetails);
                // we're finished when we have queried for both in-app and subs
                queries--;
                if (queries == 0) {
                    listener.onSkuDetailsResponse(billingResult, allSkuDetails);
                }
            }
        };
        billingClient.querySkuDetailsAsync(SkuDetailsParams.newBuilder().setSkusList(skuList).setType(SkuType.INAPP).build(), detailsListener);
        billingClient.querySkuDetailsAsync(SkuDetailsParams.newBuilder().setSkusList(skuList).setType(SkuType.SUBS).build(), detailsListener);
    }

    /**
     * Called from Lua.
     */
    public void listItems(final String products, final IListProductsListener productsListener, final long commandPtr) {
        Log.d(TAG, "listItems()");

        // create list of skus from comma separated string
        List<String> skuList = new ArrayList<String>();
        for (String p : products.split(",")) {
            if (p.trim().length() > 0) {
                skuList.add(p);
            }
        }

        querySkuDetailsAsync(skuList, new SkuDetailsResponseListener() {
            @Override
            public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> skuDetails) {
                JSONArray a = new JSONArray();
                if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                    for (SkuDetails sd : skuDetails) {
                        a.put(convertSkuDetails(sd));
                    }
                }
                else {
                    Log.e(TAG, "Unable to list products: " + billingResult.getDebugMessage());
                }
                productsListener.onProductsResult(billingResultToDefoldResponse(billingResult), a.toString(), commandPtr);
            }
        });
    }

    public void restore(final IPurchaseListener listener) {
        Log.d(TAG, "restore()");
    }
}
