#if defined(DM_PLATFORM_IOS)

#include <dmsdk/sdk.h>

#include "iap.h"
#include "iap_private.h"

#import <Foundation/Foundation.h>
#import "IAPExt-Swift.h"

#define LIB_NAME "iap"

static_assert((int)TRANS_STATE_PURCHASING == (int)IAPExtTransactionStatePurchasing, "state mismatch");
static_assert((int)TRANS_STATE_PURCHASED == (int)IAPExtTransactionStatePurchased, "state mismatch");
static_assert((int)TRANS_STATE_FAILED == (int)IAPExtTransactionStateFailed, "state mismatch");
static_assert((int)TRANS_STATE_RESTORED == (int)IAPExtTransactionStateRestored, "state mismatch");
static_assert((int)TRANS_STATE_UNVERIFIED == (int)IAPExtTransactionStateUnverified, "state mismatch");
static_assert((int)REASON_UNSPECIFIED == (int)IAPExtErrorReasonUnspecified, "reason mismatch");
static_assert((int)REASON_USER_CANCELED == (int)IAPExtErrorReasonUserCanceled, "reason mismatch");

struct IAPPendingRequest
{
    uint64_t m_Id;
    dmScript::LuaCallbackInfo* m_Callback;
};

struct IAP
{
    IAP()
    {
        memset(this, 0, sizeof(*this));
        m_AutoFinishTransactions = true;
    }
    bool                        m_AutoFinishTransactions;
    dmScript::LuaCallbackInfo*  m_Listener;
    IAPCommandQueue             m_CommandQueue;
    dmArray<IAPPendingRequest>  m_PendingRequests;
    uint64_t                    m_NextRequestId;
    IAPExtStore*                m_Store;
};

IAP g_IAP;


// The payload of the IAP_PRODUCT_RESULT command, the objects are retained
struct IAPResponse
{
    NSArray*  m_Products;
    NSString* m_Error;
};

static void IAP_FreeResponse(IAPResponse* response)
{
    [response->m_Products release];
    [response->m_Error release];
    delete response;
}

static void HandleProductResult(IAPCommand* cmd)
{
    IAPResponse* response = (IAPResponse*)cmd->m_Data;

    dmScript::LuaCallbackInfo* callback = 0;
    for (uint32_t i = 0; i < g_IAP.m_PendingRequests.Size(); ++i) {
        if (g_IAP.m_PendingRequests[i].m_Id == cmd->m_RequestId) {
            callback = g_IAP.m_PendingRequests[i].m_Callback;
            g_IAP.m_PendingRequests.EraseSwap(i);
            break;
        }
    }

    if (!dmScript::IsCallbackValid(callback)) {
        dmLogError("No callback set");
        if (callback) {
            dmScript::DestroyCallback(callback);
        }
        IAP_FreeResponse(response);
        return;
    }

    lua_State* L = dmScript::GetCallbackLuaContext(callback);
    int top = lua_gettop(L);

    if (!dmScript::SetupCallback(callback))
    {
        assert(top == lua_gettop(L));
        dmScript::DestroyCallback(callback);
        IAP_FreeResponse(response);
        return;
    }

    lua_newtable(L);

    for (IAPExtProduct* product in response->m_Products) {
        lua_pushstring(L, [product.ident UTF8String]);
        lua_newtable(L);

        lua_pushstring(L, [product.ident UTF8String]);
        lua_setfield(L, -2, "ident");
        lua_pushstring(L, [product.title UTF8String]);
        lua_setfield(L, -2, "title");
        lua_pushstring(L, [product.desc UTF8String]);
        lua_setfield(L, -2, "description");
        lua_pushnumber(L, product.price);
        lua_setfield(L, -2, "price");
        lua_pushstring(L, [product.priceString UTF8String]);
        lua_setfield(L, -2, "price_string");
        lua_pushstring(L, [product.currencyCode UTF8String]);
        lua_setfield(L, -2, "currency_code");

        lua_rawset(L, -3);
    }

    if (response->m_Error) {
        IAP_PushError(L, [response->m_Error UTF8String], REASON_UNSPECIFIED);
    } else {
        lua_pushnil(L);
    }

    dmScript::PCall(L, 3, 0);

    dmScript::TeardownCallback(callback);
    dmScript::DestroyCallback(callback);

    IAP_FreeResponse(response);
    assert(top == lua_gettop(L));
}

static void PushTransaction(lua_State* L, IAPExtTransaction* transaction)
{
    // first argument to the callback
    lua_newtable(L);

    lua_pushstring(L, [transaction.ident UTF8String]);
    lua_setfield(L, -2, "ident");
    lua_pushnumber(L, transaction.state);
    lua_setfield(L, -2, "state");
    if (transaction.date) {
        NSDateFormatter *dateFormatter = [[[NSDateFormatter alloc] init] autorelease];
        [dateFormatter setLocale: [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"]];
        [dateFormatter setDateFormat:@"yyyy-MM-dd'T'HH:mm:ssZZZ"];

        lua_pushstring(L, [[dateFormatter stringFromDate: transaction.date] UTF8String]);
        lua_setfield(L, -2, "date");
    }

    if (transaction.transIdent) {
        lua_pushstring(L, [transaction.transIdent UTF8String]);
        lua_setfield(L, -2, "trans_ident");
    }
    NSData* receipt = transaction.receipt;
    if (receipt) {
        lua_pushlstring(L, (const char*)receipt.bytes, receipt.length);
        lua_setfield(L, -2, "receipt");
    }
    if (transaction.jwsRepresentation) {
        lua_pushstring(L, [transaction.jwsRepresentation UTF8String]);
        lua_setfield(L, -2, "jws_representation");
    }

    if (transaction.originalTransaction) {
        lua_pushstring(L, "original_trans");
        PushTransaction(L, transaction.originalTransaction);
        lua_rawset(L, -3);
    }
}

static void DeliverTransaction(IAPExtTransaction* transaction)
{
    dmScript::LuaCallbackInfo* listener = g_IAP.m_Listener;
    if (!listener) {
        return;
    }

    // The same transaction can be queued twice, by Transaction.updates and iap.process_pending_transactions
    NSString* trans_ident = transaction.transIdent;
    if (!transaction.isRequested && trans_ident && [g_IAP.m_Store isTransactionFinished:trans_ident]) {
        return;
    }

    if (!dmScript::IsCallbackValid(listener)) {
        dmLogError("No callback set");
        return;
    }

    lua_State* L = dmScript::GetCallbackLuaContext(listener);
    int top = lua_gettop(L);

    if (!dmScript::SetupCallback(listener))
    {
        assert(top == lua_gettop(L));
        return;
    }

    PushTransaction(L, transaction);

    // Second argument to callback
    if (transaction.error) {
        IAP_PushError(L, [transaction.error UTF8String], transaction.errorReason);
    } else {
        lua_pushnil(L);
    }

    dmScript::PCall(L, 3, 0);

    dmScript::TeardownCallback(listener);

    assert(top == lua_gettop(L));

    if (transaction.state == IAPExtTransactionStatePurchased && g_IAP.m_AutoFinishTransactions) {
        [g_IAP.m_Store finishTransaction:trans_ident];
    }
}

static void HandlePurchaseResult(IAPCommand* cmd)
{
    IAPExtTransaction* transaction = (IAPExtTransaction*)cmd->m_Data;
    DeliverTransaction(transaction);
    [transaction release];
}

static int IAP_ProcessPendingTransactions(lua_State* L)
{
    [g_IAP.m_Store processPendingTransactions];
    return 0;
}

static int IAP_List(lua_State* L)
{
    int top = lua_gettop(L);

    NSMutableArray* product_identifiers = [[[NSMutableArray alloc] init] autorelease];

    luaL_checktype(L, 1, LUA_TTABLE);
    lua_pushnil(L);
    while (lua_next(L, 1) != 0) {
        const char* p = luaL_checkstring(L, -1);
        NSString* product_identifier = [NSString stringWithUTF8String: p];
        if (!product_identifier) {
            return luaL_error(L, "IAP: Product identifier is not a valid UTF-8 string");
        }
        [product_identifiers addObject: product_identifier];
        lua_pop(L, 1);
    }

    IAPPendingRequest request;
    request.m_Id = ++g_IAP.m_NextRequestId;
    request.m_Callback = dmScript::CreateCallback(L, 2);
    if (g_IAP.m_PendingRequests.Full()) {
        g_IAP.m_PendingRequests.OffsetCapacity(4);
    }
    g_IAP.m_PendingRequests.Push(request);

    uint64_t request_id = request.m_Id;
    [g_IAP.m_Store listProducts:product_identifiers completion:^(NSArray<IAPExtProduct*>* products, NSString* error) {
        IAPResponse* response = new IAPResponse;
        response->m_Products = [products retain];
        response->m_Error = [error retain];

        IAPCommand cmd;
        cmd.m_Command = IAP_PRODUCT_RESULT;
        cmd.m_RequestId = request_id;
        cmd.m_Data = response;
        IAP_Queue_Push(&g_IAP.m_CommandQueue, &cmd);
    }];

    assert(top == lua_gettop(L));
    return 0;
}

static int IAP_Buy(lua_State* L)
{
    int top = lua_gettop(L);

    const char* id = luaL_checkstring(L, 1);
    [g_IAP.m_Store buy:[NSString stringWithUTF8String: id]];

    assert(top == lua_gettop(L));
    return 0;
}

static int IAP_Finish(lua_State* L)
{
    int top = lua_gettop(L);

    luaL_checktype(L, 1, LUA_TTABLE);

    lua_getfield(L, -1, "state");
    int state = lua_isnumber(L, -1) ? lua_tointeger(L, -1) : -1;

    // Unverified transactions are never finished automatically
    if (g_IAP.m_AutoFinishTransactions && state != TRANS_STATE_UNVERIFIED)
    {
        dmLogWarning("Calling iap.finish when autofinish transactions is enabled. Ignored.");
        lua_pop(L, 1);
        assert(top == lua_gettop(L));
        return 0;
    }

    if (lua_isnumber(L, -1))
    {
        if(state != TRANS_STATE_PURCHASED && state != TRANS_STATE_UNVERIFIED)
        {
            dmLogError("Transaction error. Invalid transaction state for transaction finish (must be iap.TRANS_STATE_PURCHASED or iap.TRANS_STATE_UNVERIFIED).");
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
          if (![g_IAP.m_Store finishTransaction:[NSString stringWithUTF8String: str]]) {
              dmLogError("Transaction error. Invalid trans_ident value for transaction finish.");
          }
          lua_pop(L, 1);
    }

    assert(top == lua_gettop(L));
    return 0;
}

static int IAP_Restore(lua_State* L)
{
    // TODO: Missing callback here for completion/error
    int top = lua_gettop(L);
    [g_IAP.m_Store restoreWithCompletion:^(NSString* error) {
        @autoreleasepool {
            dmLogWarning("Failed to sync with the App Store: %s", [error UTF8String]);
        }
    }];
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

    IAP_Queue_Create(&g_IAP.m_CommandQueue);

    lua_State*L = params->m_L;
    int top = lua_gettop(L);
    luaL_register(L, LIB_NAME, IAP_methods);

    IAP_PushConstants(L);

    lua_pop(L, 1);
    assert(top == lua_gettop(L));

    g_IAP.m_Store = [[IAPExtStore alloc] initWithTransactionHandler:^(IAPExtTransaction* transaction) {
        IAPCommand cmd;
        cmd.m_Command = IAP_PURCHASE_RESULT;
        cmd.m_Data = [transaction retain];
        IAP_Queue_Push(&g_IAP.m_CommandQueue, &cmd);
    }];

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

static void IAP_OnDiscardCommand(IAPCommand* cmd, void*)
{
    switch (cmd->m_Command)
    {
    case IAP_PRODUCT_RESULT:
        IAP_FreeResponse((IAPResponse*)cmd->m_Data);
        break;
    case IAP_PURCHASE_RESULT:
        [(IAPExtTransaction*)cmd->m_Data release];
        break;

    default:
        assert(false);
    }
}

static dmExtension::Result UpdateIAP(dmExtension::Params* params)
{
    IAP_Queue_Flush(&g_IAP.m_CommandQueue, IAP_OnCommand, 0);
    return dmExtension::RESULT_OK;
}

static dmExtension::Result FinalizeIAP(dmExtension::Params* params)
{
    [g_IAP.m_Store stop];
    [g_IAP.m_Store release];
    g_IAP.m_Store = 0;

    IAP_Queue_Flush(&g_IAP.m_CommandQueue, IAP_OnDiscardCommand, 0);
    IAP_Queue_Destroy(&g_IAP.m_CommandQueue);

    for (uint32_t i = 0; i < g_IAP.m_PendingRequests.Size(); ++i) {
        dmScript::DestroyCallback(g_IAP.m_PendingRequests[i].m_Callback);
    }
    g_IAP.m_PendingRequests.SetSize(0);

    if (g_IAP.m_Listener)
    {
        dmScript::DestroyCallback(g_IAP.m_Listener);
    }

    g_IAP.m_Listener = 0;

    return dmExtension::RESULT_OK;
}


DM_DECLARE_EXTENSION(IAPExt, "IAP", 0, 0, InitializeIAP, UpdateIAP, 0, FinalizeIAP)

#endif // DM_PLATFORM_IOS
