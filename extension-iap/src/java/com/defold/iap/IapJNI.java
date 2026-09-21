package com.defold.iap;

public class IapJNI implements IListProductsListener, IPurchaseListener {

    // NOTE: Also defined in iap.h
    public static final int TRANS_STATE_PURCHASING = 0;
    public static final int TRANS_STATE_PURCHASED = 1;
    public static final int TRANS_STATE_FAILED = 2;
    public static final int TRANS_STATE_RESTORED = 3;
    public static final int TRANS_STATE_UNVERIFIED = 4;

    public static final int BILLING_RESPONSE_RESULT_OK = 0;
    public static final int BILLING_RESPONSE_RESULT_USER_CANCELED = 1;
    public static final int BILLING_RESPONSE_RESULT_SERVICE_UNAVAILABLE = 2;
    public static final int BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE = 3;
    public static final int BILLING_RESPONSE_RESULT_ITEM_UNAVAILABLE = 4;
    public static final int BILLING_RESPONSE_RESULT_DEVELOPER_ERROR = 5;
    public static final int BILLING_RESPONSE_RESULT_ERROR = 6;
    public static final int BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED = 7;
    public static final int BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED = 8;
    public static final int BILLING_RESPONSE_RESULT_NETWORK_ERROR = 9;

    public IapJNI() {
    }

    private boolean active = true;

    // Wait for an in-flight native callback before the engine destroys its queue.
    // A new extension session must use a new IapJNI instance.
    public synchronized void invalidate() {
        active = false;
    }

    @Override
    public synchronized void onProductsResult(int responseCode, String productList, long requestId) {
        if (active) {
            nativeOnProductsResult(responseCode, productList, requestId);
        }
    }

    @Override
    public synchronized void onPurchaseResult(int responseCode, String purchaseData) {
        if (active) {
            nativeOnPurchaseResult(responseCode, purchaseData);
        }
    }

    private native void nativeOnProductsResult(int responseCode, String productList, long requestId);
    private native void nativeOnPurchaseResult(int responseCode, String purchaseData);

}
