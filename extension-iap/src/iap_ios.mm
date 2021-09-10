#if defined(DM_PLATFORM_IOS)

#include <dmsdk/sdk.h>

#include "iap.h"
#include "iap_private.h"

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <StoreKit/StoreKit.h>

#define LIB_NAME "iap"

struct IAP;

@interface SKPaymentTransactionObserver : NSObject<SKPaymentTransactionObserver>
    @property IAP* m_IAP;
@end

struct IAP
{
    IAP()
    {
        memset(this, 0, sizeof(*this));
        m_AutoFinishTransactions = true;
    }
    int                             m_Version;
    bool                            m_AutoFinishTransactions;
    NSMutableDictionary*            m_PendingTransactions;
    dmScript::LuaCallbackInfo*      m_Listener;
    IAPCommandQueue                 m_CommandQueue;
    IAPCommandQueue                 m_ObservableQueue;
    SKPaymentTransactionObserver*   m_Observer;
};

IAP g_IAP;


// The payload of the purchasing commands
struct IAPProduct
{
    const char* ident;
    const char* title;
    const char* description;
    float       price;
    const char* price_string;
    const char* currency_code;
};

struct IAPResponse
{
    const char*         error; // optional
    int32_t             error_code; // only valid if error is set
    dmArray<IAPProduct> m_Products;

    IAPResponse() {
        memset(this, 0, sizeof(*this));
    }
};

struct IAPTransaction
{
    const char*     ident;
    int32_t         state;
    const char*     date;
    const char*     trans_ident; // optional
    const char*     receipt; // optional
    uint32_t        receipt_length;
    const char*     original_trans; // optional
    const char*     error; // optional
    int32_t         error_code; // only valid if error is set
    IAPTransaction* m_OriginalTransaction;

    IAPTransaction() {
        memset(this, 0, sizeof(*this));
    }
};

static void IAP_FreeProduct(IAPProduct* product)
{
    free((void*)product->ident);
    free((void*)product->title);
    free((void*)product->description);
    free((void*)product->price_string);
    free((void*)product->currency_code);
}

static void IAP_FreeTransaction(IAPTransaction* transaction)
{
    if (transaction->m_OriginalTransaction) {
        IAP_FreeTransaction(transaction->m_OriginalTransaction);
    }
    delete transaction->m_OriginalTransaction;
    free((void*)transaction->ident);
    free((void*)transaction->date);
    free((void*)transaction->trans_ident);
    free((void*)transaction->receipt);
    free((void*)transaction->original_trans);
    free((void*)transaction->error);
}


@interface SKProductsRequestDelegate : NSObject<SKProductsRequestDelegate>
    @property dmScript::LuaCallbackInfo* m_Callback;
    @property (assign) SKProductsRequest* m_Request;
    @property int m_Version;
@end

@implementation SKProductsRequestDelegate
- (void)productsRequest:(SKProductsRequest *)request didReceiveResponse:(SKProductsResponse *)response{

    if (self.m_Version != g_IAP.m_Version) {
        dmLogWarning("Received products but the extension has been restarted")
        return;
    }

    if (!dmScript::IsCallbackValid(self.m_Callback)) {
        dmLogError("No callback set");
        return;
    }

    NSArray* skProducts = response.products;

    IAPResponse* iap_response = new IAPResponse;
    for (SKProduct * p in skProducts) {

        IAPProduct product = {0};
        product.ident           = strdup([p.productIdentifier UTF8String]);
        if (p.localizedTitle) {
            product.title       = strdup([p.localizedTitle UTF8String]);
        }
        else {
            dmLogWarning("Product %s has no localizedTitle", [p.productIdentifier UTF8String]);
            product.title       = strdup("");
        }
        if (p.localizedDescription) {
            product.description = strdup([p.localizedDescription UTF8String]);
        }
        else {
            dmLogWarning("Product %s has no localizedDescription", [p.productIdentifier UTF8String]);
            product.description = strdup("");
        }
        product.currency_code   = strdup([[p.priceLocale objectForKey:NSLocaleCurrencyCode] UTF8String]);
        product.price           = p.price.floatValue;

        NSNumberFormatter *formatter = [[[NSNumberFormatter alloc] init] autorelease];
        [formatter setNumberStyle: NSNumberFormatterCurrencyStyle];
        [formatter setLocale: p.priceLocale];
        NSString* price_string = [formatter stringFromNumber: p.price];
        product.price_string = strdup([price_string UTF8String]);

        if (iap_response->m_Products.Full()) {
            iap_response->m_Products.OffsetCapacity(2);
        }
        iap_response->m_Products.Push(product);
    }


    IAPCommand cmd;
    cmd.m_Command = IAP_PRODUCT_RESULT;
    cmd.m_Callback = self.m_Callback;
    cmd.m_Data = iap_response;
    IAP_Queue_Push(&g_IAP.m_CommandQueue, &cmd);
}

static void HandleProductResult(IAPCommand* cmd)
{

    IAPResponse* response = (IAPResponse*)cmd->m_Data;

    lua_State* L = dmScript::GetCallbackLuaContext(cmd->m_Callback);
    int top = lua_gettop(L);

    if (!dmScript::SetupCallback(cmd->m_Callback))
    {
        assert(top == lua_gettop(L));
        delete response;
        return;
    }

    lua_newtable(L);

    for (uint32_t i = 0; i < response->m_Products.Size(); ++i) {
        IAPProduct* product = &response->m_Products[i];

        lua_pushstring(L, product->ident);
        lua_newtable(L);

        lua_pushstring(L, product->ident);
        lua_setfield(L, -2, "ident");
        lua_pushstring(L, product->title);
        lua_setfield(L, -2, "title");
        lua_pushstring(L, product->description);
        lua_setfield(L, -2, "description");
        lua_pushnumber(L, product->price);
        lua_setfield(L, -2, "price");
        lua_pushstring(L, product->price_string);
        lua_setfield(L, -2, "price_string");
        lua_pushstring(L, product->currency_code);
        lua_setfield(L, -2, "currency_code");

        lua_rawset(L, -3);

        IAP_FreeProduct(product);
    }
    lua_pushnil(L);

    dmScript::PCall(L, 3, 0);

    dmScript::TeardownCallback(cmd->m_Callback);
    dmScript::DestroyCallback(cmd->m_Callback);

    delete response;
    assert(top == lua_gettop(L));
}

- (void)request:(SKRequest *)request didFailWithError:(NSError *)error{
    dmLogWarning("SKProductsRequest failed: %s", [error.localizedDescription UTF8String]);

    if (!dmScript::IsCallbackValid(self.m_Callback)) {
        dmLogError("No callback set");
        return;
    }

    IAPResponse* response = new IAPResponse;
    response->error = strdup([error.localizedDescription UTF8String]);
    response->error_code = REASON_UNSPECIFIED;

    IAPCommand cmd;
    cmd.m_Command = IAP_PRODUCT_RESULT;
    cmd.m_Callback = self.m_Callback;
    cmd.m_Data = response;

    IAP_Queue_Push(&g_IAP.m_CommandQueue, &cmd);
}

- (void)requestDidFinish:(SKRequest *)request
{
    [self.m_Request release];
    [self release];
}

@end

static void CopyTransaction(SKPaymentTransaction* transaction, IAPTransaction* out)
{
    if (transaction.transactionState == SKPaymentTransactionStateFailed) {
        out->error = strdup([transaction.error.localizedDescription UTF8String]);
        out->error_code = transaction.error.code == SKErrorPaymentCancelled ? REASON_USER_CANCELED : REASON_UNSPECIFIED;
    } else {
        out->error = 0;
    }

    NSDateFormatter *dateFormatter = [[[NSDateFormatter alloc] init] autorelease];
    [dateFormatter setLocale: [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"]];
    [dateFormatter setDateFormat:@"yyyy-MM-dd'T'HH:mm:ssZZZ"];

    out->ident = strdup([transaction.payment.productIdentifier UTF8String]);


    if (transaction.transactionDate)
        out->date  = strdup([[dateFormatter stringFromDate: transaction.transactionDate] UTF8String]);
    out->state = transaction.transactionState;

    if (transaction.transactionState == SKPaymentTransactionStatePurchased ||
        transaction.transactionState == SKPaymentTransactionStateRestored) {
        out->trans_ident = strdup([transaction.transactionIdentifier UTF8String]);
    }

    if (transaction.transactionState == SKPaymentTransactionStatePurchased) {
        NSURL *receiptURL = [[NSBundle mainBundle] appStoreReceiptURL];
        NSData *receiptData = [NSData dataWithContentsOfURL:receiptURL];
        out->receipt_length = receiptData.length;
        out->receipt = (const char*)malloc(out->receipt_length);
        memcpy((void*)out->receipt, receiptData.bytes, out->receipt_length);
    }

    if (transaction.transactionState == SKPaymentTransactionStateRestored && transaction.originalTransaction) {
        out->m_OriginalTransaction = new IAPTransaction;
        CopyTransaction(transaction.originalTransaction, out->m_OriginalTransaction);
    }
}

static void PushTransaction(lua_State* L, IAPTransaction* transaction)
{
    // first argument to the callback
    lua_newtable(L);

    lua_pushstring(L, transaction->ident);
    lua_setfield(L, -2, "ident");
    lua_pushnumber(L, transaction->state);
    lua_setfield(L, -2, "state");
    if (transaction->date) {
        lua_pushstring(L, transaction->date);
        lua_setfield(L, -2, "date");
    }

    if (transaction->trans_ident) {
        lua_pushstring(L, transaction->trans_ident);
        lua_setfield(L, -2, "trans_ident");
    }
    if (transaction->receipt) {
        lua_pushlstring(L, transaction->receipt, transaction->receipt_length);
        lua_setfield(L, -2, "receipt");
    }

    if (transaction->m_OriginalTransaction) {
        lua_pushstring(L, "original_trans");
        PushTransaction(L, transaction->m_OriginalTransaction);
        lua_rawset(L, -3);
    }
}


static void HandlePurchaseResult(IAPCommand* cmd)
{
    IAPTransaction* transaction = (IAPTransaction*)cmd->m_Data;

    lua_State* L = dmScript::GetCallbackLuaContext(cmd->m_Callback);
    int top = lua_gettop(L);

    if (!dmScript::SetupCallback(cmd->m_Callback))
    {
        assert(top == lua_gettop(L));
        return;
    }

    PushTransaction(L, transaction);

    // Second argument to callback
    if (transaction->error) {
        IAP_PushError(L, transaction->error, transaction->error_code);
    } else {
        lua_pushnil(L);
    }

    dmScript::PCall(L, 3, 0);

    dmScript::TeardownCallback(cmd->m_Callback);

    IAP_FreeTransaction(transaction);

    delete transaction;
    assert(top == lua_gettop(L));
}

static void processTransactions(IAP* iap, NSArray* transactions) {
    for (SKPaymentTransaction* transaction in transactions) {
            if ((!iap->m_AutoFinishTransactions) && (transaction.transactionState == SKPaymentTransactionStatePurchased)) {
                NSData *data = [transaction.transactionIdentifier dataUsingEncoding:NSUTF8StringEncoding];
                uint64_t trans_id_hash = dmHashBuffer64((const char*) [data bytes], [data length]);
                [iap->m_PendingTransactions setObject:transaction forKey:[NSNumber numberWithInteger:trans_id_hash] ];
            }

            if (!iap->m_Listener)
                continue;

            IAPTransaction* iap_transaction = new IAPTransaction;
            CopyTransaction(transaction, iap_transaction);

            IAPCommand cmd;
            cmd.m_Callback = iap->m_Listener;
            cmd.m_Command = IAP_PURCHASE_RESULT;
            cmd.m_Data = iap_transaction;
            IAP_Queue_Push(&iap->m_ObservableQueue, &cmd);

            switch (transaction.transactionState)
            {
                case SKPaymentTransactionStatePurchased:
                    if (g_IAP.m_AutoFinishTransactions) {
                        [[SKPaymentQueue defaultQueue] finishTransaction:transaction];
                    }
                    break;
                case SKPaymentTransactionStateFailed:
                    [[SKPaymentQueue defaultQueue] finishTransaction:transaction];
                    break;
                case SKPaymentTransactionStateRestored:
                    [[SKPaymentQueue defaultQueue] finishTransaction:transaction];
                    break;
                default:
                    break;
            }
    }
}

static int IAP_ProcessPendingTransactions(lua_State* L)
{
    processTransactions(&g_IAP, [SKPaymentQueue defaultQueue].transactions);
    return 0;
}

@implementation SKPaymentTransactionObserver
    - (void)paymentQueue:(SKPaymentQueue *)queue updatedTransactions:(NSArray *)transactions
    {
        processTransactions(self.m_IAP, transactions);
    }
@end

static int IAP_List(lua_State* L)
{
    int top = lua_gettop(L);

    NSCountedSet* product_identifiers = [[[NSCountedSet alloc] init] autorelease];

    luaL_checktype(L, 1, LUA_TTABLE);
    lua_pushnil(L);
    while (lua_next(L, 1) != 0) {
        const char* p = luaL_checkstring(L, -1);
        [product_identifiers addObject: [NSString stringWithUTF8String: p]];
        lua_pop(L, 1);
    }

    SKProductsRequest* products_request = [[SKProductsRequest alloc] initWithProductIdentifiers: product_identifiers];
    SKProductsRequestDelegate* delegate = [SKProductsRequestDelegate alloc];

    delegate.m_Callback = dmScript::CreateCallback(L, 2);
    delegate.m_Request = products_request;
    delegate.m_Version = g_IAP.m_Version;
    products_request.delegate = delegate;
    [products_request start];

    assert(top == lua_gettop(L));
    return 0;
}

static int IAP_Buy(lua_State* L)
{
    int top = lua_gettop(L);

    const char* id = luaL_checkstring(L, 1);
    SKMutablePayment* payment = [[SKMutablePayment alloc] init];
    payment.productIdentifier = [NSString stringWithUTF8String: id];
    payment.quantity = 1;

    [[SKPaymentQueue defaultQueue] addPayment:payment];
    [payment release];

    assert(top == lua_gettop(L));
    return 0;
}

static int IAP_Finish(lua_State* L)
{
    if(g_IAP.m_AutoFinishTransactions)
    {
        dmLogWarning("Calling iap.finish when autofinish transactions is enabled. Ignored.");
        return 0;
    }

    int top = lua_gettop(L);

    luaL_checktype(L, 1, LUA_TTABLE);

    lua_getfield(L, -1, "state");
    if (lua_isnumber(L, -1))
    {
        if(lua_tointeger(L, -1) != SKPaymentTransactionStatePurchased)
        {
            dmLogError("Transaction error. Invalid transaction state for transaction finish (must be iap.TRANS_STATE_PURCHASED).");
            lua_pop(L, 1);
            assert(top == lua_gettop(L));
            return 0;
        }
    }
    lua_pop(L, 1);

    lua_getfield(L, -1, "trans_ident");
    if (!lua_isstring(L, -1)) {
        dmLogError("Transaction error. Invalid transaction data for transaction finish, does not contain 'trans_ident' key.");
        lua_pop(L, 1);
    }
    else
    {
          const char *str = lua_tostring(L, -1);
          uint64_t trans_ident_hash = dmHashBuffer64(str, strlen(str));
          lua_pop(L, 1);
          SKPaymentTransaction * transaction = [g_IAP.m_PendingTransactions objectForKey:[NSNumber numberWithInteger:trans_ident_hash]];
          if(transaction == 0x0) {
              dmLogError("Transaction error. Invalid trans_ident value for transaction finish.");
          } else {
              [[SKPaymentQueue defaultQueue] finishTransaction:transaction];
              [g_IAP.m_PendingTransactions removeObjectForKey:[NSNumber numberWithInteger:trans_ident_hash]];
          }
    }

    assert(top == lua_gettop(L));
    return 0;
}

static int IAP_Restore(lua_State* L)
{
    // TODO: Missing callback here for completion/error
    // See callback under "Handling Restored Transactions"
    // https://developer.apple.com/library/ios/documentation/StoreKit/Reference/SKPaymentTransactionObserver_Protocol/Reference/Reference.html
    int top = lua_gettop(L);
    [[SKPaymentQueue defaultQueue] restoreCompletedTransactions];
    assert(top == lua_gettop(L));
    lua_pushboolean(L, 1);
    return 1;
}

static int IAP_SetListener(lua_State* L)
{
    IAP* iap = &g_IAP;

    if (iap->m_Listener)
        dmScript::DestroyCallback(iap->m_Listener);

    iap->m_Listener = dmScript::CreateCallback(L, 1);
    return 0;
}

static int IAP_Acknowledge(lua_State* L)
{
    return 0;
}

static int IAP_GetProviderId(lua_State* L)
{
    lua_pushinteger(L, PROVIDER_ID_APPLE);
    return 1;
}

static const luaL_reg IAP_methods[] =
{
    {"list", IAP_List},
    {"buy", IAP_Buy},
    {"finish", IAP_Finish},
    {"acknowledge", IAP_Acknowledge},
    {"restore", IAP_Restore},
    {"set_listener", IAP_SetListener},
    {"get_provider_id", IAP_GetProviderId},
    {"process_pending_transactions", IAP_ProcessPendingTransactions},
    {0, 0}
};

static dmExtension::Result InitializeIAP(dmExtension::Params* params)
{
    g_IAP.m_AutoFinishTransactions = dmConfigFile::GetInt(params->m_ConfigFile, "iap.auto_finish_transactions", 1) == 1;
    g_IAP.m_PendingTransactions = [[NSMutableDictionary alloc]initWithCapacity:2];
    g_IAP.m_Version++;

    IAP_Queue_Create(&g_IAP.m_CommandQueue);
    IAP_Queue_Create(&g_IAP.m_ObservableQueue);

    lua_State*L = params->m_L;
    int top = lua_gettop(L);
    luaL_register(L, LIB_NAME, IAP_methods);

    // ensure ios payment constants values corresponds to iap constants.
    assert((int)TRANS_STATE_PURCHASING == (int)SKPaymentTransactionStatePurchasing);
    assert((int)TRANS_STATE_PURCHASED == (int)SKPaymentTransactionStatePurchased);
    assert((int)TRANS_STATE_FAILED == (int)SKPaymentTransactionStateFailed);
    assert((int)TRANS_STATE_RESTORED == (int)SKPaymentTransactionStateRestored);

    IAP_PushConstants(L);

    lua_pop(L, 1);
    assert(top == lua_gettop(L));

    SKPaymentTransactionObserver* observer = [[SKPaymentTransactionObserver alloc] init];
    observer.m_IAP = &g_IAP;
    [[SKPaymentQueue defaultQueue] addTransactionObserver: observer];
    g_IAP.m_Observer = observer;

    return dmExtension::RESULT_OK;
}

static void IAP_OnCommand(IAPCommand* cmd, void*)
{
    switch (cmd->m_Command)
    {
    case IAP_PRODUCT_RESULT:
        HandleProductResult(cmd);
        break;
    case IAP_PURCHASE_RESULT:
        HandlePurchaseResult(cmd);
        break;

    default:
        assert(false);
    }
}

static dmExtension::Result UpdateIAP(dmExtension::Params* params)
{
    IAP_Queue_Flush(&g_IAP.m_CommandQueue, IAP_OnCommand, 0);
    if (g_IAP.m_Observer) {
        IAP_Queue_Flush(&g_IAP.m_ObservableQueue, IAP_OnCommand, 0);
    }
    return dmExtension::RESULT_OK;
}

static dmExtension::Result FinalizeIAP(dmExtension::Params* params)
{
    if(g_IAP.m_Listener){
         dmScript::DestroyCallback(g_IAP.m_Listener);
    }
   
    g_IAP.m_Listener = 0;

    if (g_IAP.m_PendingTransactions) {
         [g_IAP.m_PendingTransactions release];
         g_IAP.m_PendingTransactions = 0;
    }

    if (g_IAP.m_Observer) {
        [[SKPaymentQueue defaultQueue] removeTransactionObserver: g_IAP.m_Observer];
        [g_IAP.m_Observer release];
        g_IAP.m_Observer = 0;
    }

    IAP_Queue_Destroy(&g_IAP.m_CommandQueue);
    IAP_Queue_Destroy(&g_IAP.m_ObservableQueue);

    return dmExtension::RESULT_OK;
}


DM_DECLARE_EXTENSION(IAPExt, "IAP", 0, 0, InitializeIAP, UpdateIAP, 0, FinalizeIAP)

#endif // DM_PLATFORM_IOS
