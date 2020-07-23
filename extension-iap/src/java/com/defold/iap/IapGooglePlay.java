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

    private BillingClient billingClient;

    private Map<String, SkuDetails> products;

    private IPurchaseListener purchaseListener;
    private boolean autoFinishTransactions;
    private Activity activity;

    public IapGooglePlay(Activity activity, boolean autoFinishTransactions) {
        this.activity = activity;
        this.autoFinishTransactions = autoFinishTransactions;
        products = new HashMap<String, SkuDetails>();

        billingClient = BillingClient.newBuilder(activity).setListener(this).enablePendingPurchases().build();
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                    Log.v(TAG, "Setup finished");
                    // TODO BillingClient.queryPurchases()
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

    private int billingResponseCodeToDefoldResponse(int responseCode)
    {
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

    // -------------------------------------------

    private List<Purchase> queryPurchases(final String type) {
        PurchasesResult result = billingClient.queryPurchases(type);
        if (result.getBillingResult().getResponseCode() != BillingResponseCode.OK) {
            Log.e(TAG, "Unable to query pending purchases: " + result.getBillingResult().getDebugMessage());
            return new ArrayList<Purchase>();
        }
        return result.getPurchasesList();
    }

    public void processPendingConsumables(final IPurchaseListener purchaseListener)
    {
        Log.d(TAG, "processPendingConsumables()");
        List<Purchase> purchasesList = new ArrayList<Purchase>();
        purchasesList.addAll(queryPurchases(SkuType.INAPP));
        purchasesList.addAll(queryPurchases(SkuType.SUBS));
        for (Purchase purchase : purchasesList) {
            handlePurchase(purchase, purchaseListener);
        }
    }

    // -------------------------------------------

    private void consumePurchase(final Purchase purchase, final IPurchaseListener purchaseListener)
    {
        Log.d(TAG, "consumePurchase()");
        ConsumeParams consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.getPurchaseToken())
            .build();

        billingClient.consumeAsync(consumeParams, new ConsumeResponseListener() {
            @Override
            public void onConsumeResponse(BillingResult billingResult, String purchaseToken) {
                Log.d(TAG, "consumePurchase() onConsumeResponse " + billingResult.getResponseCode() + " purchaseToken: " + purchaseToken);
                if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                    Log.e(TAG, "Unable to consume purchase: " + billingResult.getDebugMessage());
                    purchaseListener.onPurchaseResult(billingResultToDefoldResponse(billingResult), "");
                    return;
                }
            }
        });
    }

    public void finishTransaction(final String purchaseToken, final IPurchaseListener purchaseListener) {
        Log.d(TAG, "finishTransaction() " + purchaseToken);
        List<Purchase> purchasesList = new ArrayList<Purchase>();
        purchasesList.addAll(queryPurchases(SkuType.INAPP));
        purchasesList.addAll(queryPurchases(SkuType.SUBS));

        for(Purchase p : purchasesList) {
            Log.d(TAG, "finishTransaction() purchase: " + p.getOriginalJson());
            if (p.getPurchaseToken().equals(purchaseToken)) {
                consumePurchase(p, purchaseListener);
                return;
            }
        }
        Log.e(TAG, "Unable to find purchase for token: " + purchaseToken);
        purchaseListener.onPurchaseResult(IapJNI.BILLING_RESPONSE_RESULT_ERROR, "");
    }

    // -------------------------------------------

    private void handlePurchase(final Purchase purchase, final IPurchaseListener purchaseListener) {
        if (this.autoFinishTransactions) {
            consumePurchase(purchase, purchaseListener);
        }
        else {
            purchaseListener.onPurchaseResult(billingResponseCodeToDefoldResponse(BillingResponseCode.OK), convertPurchase(purchase));
        }
    }

    @Override
    public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases) {
        if (billingResult.getResponseCode() == BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase, this.purchaseListener);
            }
        } else {
            this.purchaseListener.onPurchaseResult(billingResultToDefoldResponse(billingResult), "");
        }
    }

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

    // -------------------------------------------

    private void querySkuDetailsAsync(final List<String> skuList, final SkuDetailsResponseListener listener)
    {
        SkuDetailsResponseListener detailsListener = new SkuDetailsResponseListener() {
            private List<SkuDetails> allSkuDetails = new ArrayList<SkuDetails>();
            private int queries = 2;

            @Override
            public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> skuDetails) {
                // cache skus (cache will be used to speed up buying)
                for (SkuDetails sd : skuDetails)
                {
                    IapGooglePlay.this.products.put(sd.getSku(), sd);
                }
                //
                allSkuDetails.addAll(skuDetails);
                queries--;
                if (queries == 0)
                {
                    listener.onSkuDetailsResponse(billingResult, allSkuDetails);
                }
            }
        };
        billingClient.querySkuDetailsAsync(SkuDetailsParams.newBuilder().setSkusList(skuList).setType(SkuType.INAPP).build(), detailsListener);
        billingClient.querySkuDetailsAsync(SkuDetailsParams.newBuilder().setSkusList(skuList).setType(SkuType.SUBS).build(), detailsListener);
    }

    public void listItems(final String products, final IListProductsListener productsListener, final long commandPtr)
    {
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
                    for (SkuDetails sd : skuDetails)
                    {
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

    // -------------------------------------------

    public void stop()
    {
        Log.d(TAG, "stop()");
    }

    public void restore(final IPurchaseListener listener)
    {
        Log.d(TAG, "restore()");
    }
}
