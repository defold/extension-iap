#if defined(DM_PLATFORM_ANDROID)

#include <dmsdk/sdk.h>
#include <dmsdk/dlib/android.h>

#include <stdlib.h>
#include <unistd.h>
#include "iap.h"
#include "iap_private.h"

#define LIB_NAME "iap"

struct IAP
{
    IAP()
    {
        memset(this, 0, sizeof(*this));
        m_autoFinishTransactions = true;
        m_ProviderId = PROVIDER_ID_GOOGLE;
    }
    bool            m_autoFinishTransactions;
    int             m_ProviderId;

    dmScript::LuaCallbackInfo* m_Listener;

    jobject         m_IAP;
    jobject         m_IAPJNI;
    jmethodID       m_List;
    jmethodID       m_Stop;
    jmethodID       m_Buy;
    jmethodID       m_Restore;
    jmethodID       m_ProcessPendingConsumables;
    jmethodID       m_AcknowledgeTransaction;
    jmethodID       m_FinishTransaction;

    IAPCommandQueue m_CommandQueue;
};

static IAP g_IAP;

static int IAP_ProcessPendingTransactions(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    dmAndroid::ThreadAttacher threadAttacher;
    JNIEnv* env = threadAttacher.GetEnv();
    env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_ProcessPendingConsumables, g_IAP.m_IAPJNI);

    return 0;
}

static int IAP_List(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    char* buf = IAP_List_CreateBuffer(L);
    if( buf == 0 )
    {
        return 0;
    }

    dmAndroid::ThreadAttacher threadAttacher;
    JNIEnv* env = threadAttacher.GetEnv();
    IAPCommand* cmd = new IAPCommand;
    cmd->m_Callback = dmScript::CreateCallback(L, 2);
    cmd->m_Command = IAP_PRODUCT_RESULT;

    jstring products = env->NewStringUTF(buf);
    env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_List, products, g_IAP.m_IAPJNI, (jlong)cmd);
    env->DeleteLocalRef(products);

    free(buf);
    return 0;
}

static int IAP_Buy(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    int top = lua_gettop(L);
    const char* id = luaL_checkstring(L, 1);
    const char* token = "";

    if (top >= 2 && lua_istable(L, 2)) {
        luaL_checktype(L, 2, LUA_TTABLE);
        lua_pushvalue(L, 2);
        lua_getfield(L, -1, "token");
        token = lua_isnil(L, -1) ? "" : luaL_checkstring(L, -1);
        lua_pop(L, 2);
    }

    dmAndroid::ThreadAttacher threadAttacher;
    JNIEnv* env = threadAttacher.GetEnv();
    jstring ids = env->NewStringUTF(id);
    jstring tokens = env->NewStringUTF(token);
    env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_Buy, ids, tokens, g_IAP.m_IAPJNI);
    env->DeleteLocalRef(ids);
    env->DeleteLocalRef(tokens);

    return 0;
}

static int IAP_Finish(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    if(g_IAP.m_autoFinishTransactions)
    {
        dmLogWarning("Calling iap.finish when autofinish transactions is enabled. Ignored.");
        return 0;
    }

    luaL_checktype(L, 1, LUA_TTABLE);

    lua_getfield(L, -1, "state");
    if (lua_isnumber(L, -1))
    {
        if(lua_tointeger(L, -1) != TRANS_STATE_PURCHASED)
        {
            dmLogError("Invalid transaction state (must be iap.TRANS_STATE_PURCHASED).");
            lua_pop(L, 1);
            return 0;
        }
    }
    lua_pop(L, 1);

    lua_getfield(L, -1, "receipt");
    if (!lua_isstring(L, -1)) {
        dmLogError("Transaction error. Invalid transaction data, does not contain 'receipt' key.");
        lua_pop(L, 1);
    }
    else
    {
        const char * receipt = lua_tostring(L, -1);
        lua_pop(L, 1);

        dmAndroid::ThreadAttacher threadAttacher;
        JNIEnv* env = threadAttacher.GetEnv();
        jstring receiptUTF = env->NewStringUTF(receipt);
        env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_FinishTransaction, receiptUTF, g_IAP.m_IAPJNI);
        env->DeleteLocalRef(receiptUTF);
    }

    return 0;
}

static int IAP_Acknowledge(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    luaL_checktype(L, 1, LUA_TTABLE);

    lua_getfield(L, -1, "state");
    if (lua_isnumber(L, -1))
    {
        if(lua_tointeger(L, -1) != TRANS_STATE_PURCHASED)
        {
            dmLogError("Invalid transaction state (must be iap.TRANS_STATE_PURCHASED).");
            lua_pop(L, 1);
            return 0;
        }
    }
    lua_pop(L, 1);

    lua_getfield(L, -1, "receipt");
    if (!lua_isstring(L, -1)) {
        dmLogError("Transaction error. Invalid transaction data, does not contain 'receipt' key.");
        lua_pop(L, 1);
    }
    else
    {
        const char * receipt = lua_tostring(L, -1);
        lua_pop(L, 1);

        dmAndroid::ThreadAttacher threadAttacher;
        JNIEnv* env = threadAttacher.GetEnv();
        jstring receiptUTF = env->NewStringUTF(receipt);
        env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_AcknowledgeTransaction, receiptUTF, g_IAP.m_IAPJNI);
        env->DeleteLocalRef(receiptUTF);
    }

    return 0;
}

static int IAP_Restore(lua_State* L)
{
    // TODO: Missing callback here for completion/error
    // See iap_ios.mm
    DM_LUA_STACK_CHECK(L, 1);

    dmAndroid::ThreadAttacher threadAttacher;
    JNIEnv* env = threadAttacher.GetEnv();
    env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_Restore, g_IAP.m_IAPJNI);

    lua_pushboolean(L, 1);
    return 1;
}

static int IAP_SetListener(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 0);

    IAP* iap = &g_IAP;

    bool had_previous = iap->m_Listener != 0;

    if (iap->m_Listener)
        dmScript::DestroyCallback(iap->m_Listener);

    iap->m_Listener = dmScript::CreateCallback(L, 1);

    // On first set listener, trigger process old ones.
    if (!had_previous) {
        dmAndroid::ThreadAttacher threadAttacher;
        JNIEnv* env = threadAttacher.GetEnv();
        env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_ProcessPendingConsumables, g_IAP.m_IAPJNI);
    }
    return 0;
}

static int IAP_GetProviderId(lua_State* L)
{
    DM_LUA_STACK_CHECK(L, 1);

    lua_pushinteger(L, g_IAP.m_ProviderId);
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


#ifdef __cplusplus
extern "C" {
#endif


JNIEXPORT void JNICALL Java_com_defold_iap_IapJNI_onProductsResult(JNIEnv* env, jobject, jint responseCode, jstring productList, jlong cmdHandle)
{
    const char* pl = 0;
    if (productList)
    {
        pl = env->GetStringUTFChars(productList, 0);
    }

    IAPCommand* cmd = (IAPCommand*)cmdHandle;
    cmd->m_ResponseCode = responseCode;
    if (pl)
    {
        cmd->m_Data = strdup(pl);
        env->ReleaseStringUTFChars(productList, pl);
    }
    IAP_Queue_Push(&g_IAP.m_CommandQueue, cmd);
}

JNIEXPORT void JNICALL Java_com_defold_iap_IapJNI_onPurchaseResult__ILjava_lang_String_2(JNIEnv* env, jobject, jint responseCode, jstring purchaseData)
{
    dmLogInfo("Java_com_defold_iap_IapJNI_onPurchaseResult__ILjava_lang_String_2 %d", (int)responseCode);
    const char* pd = 0;
    if (purchaseData)
    {
        pd = env->GetStringUTFChars(purchaseData, 0);
    }

    IAPCommand cmd;
    cmd.m_Callback = g_IAP.m_Listener;
    cmd.m_Command = IAP_PURCHASE_RESULT;
    cmd.m_ResponseCode = responseCode;
    if (pd)
    {
        cmd.m_Data = strdup(pd);
        env->ReleaseStringUTFChars(purchaseData, pd);
    }
    IAP_Queue_Push(&g_IAP.m_CommandQueue, &cmd);
}

#ifdef __cplusplus
}
#endif

static void HandleProductResult(const IAPCommand* cmd)
{
    if (cmd->m_Callback == 0)
    {
        dmLogWarning("Received product list but no listener was set!");
        return;
    }

    lua_State* L = dmScript::GetCallbackLuaContext(cmd->m_Callback);
    int top = lua_gettop(L);

    if (!dmScript::SetupCallback(cmd->m_Callback))
    {
        assert(top == lua_gettop(L));
        return;
    }

    if (cmd->m_ResponseCode == BILLING_RESPONSE_RESULT_OK) {
        const char* json = (const char*)cmd->m_Data;
        dmScript::JsonToLua(L, json, strlen(json)); // throws lua error if it fails
        lua_pushnil(L);
    } else {
        dmLogError("IAP error %d", cmd->m_ResponseCode);
        lua_pushnil(L);
        IAP_PushError(L, "failed to fetch product", REASON_UNSPECIFIED);
    }

    dmScript::PCall(L, 3, 0);

    dmScript::TeardownCallback(cmd->m_Callback);
    dmScript::DestroyCallback(cmd->m_Callback);

    assert(top == lua_gettop(L));
}

static void HandlePurchaseResult(const IAPCommand* cmd)
{
    if (cmd->m_Callback == 0)
    {
        dmLogWarning("Received purchase result but no listener was set!");
        return;
    }

    lua_State* L = dmScript::GetCallbackLuaContext(cmd->m_Callback);
    int top = lua_gettop(L);

    if (!dmScript::SetupCallback(cmd->m_Callback))
    {
        assert(top == lua_gettop(L));
        return;
    }

    if (cmd->m_ResponseCode == BILLING_RESPONSE_RESULT_OK) {
        if (cmd->m_Data != 0) {
            const char* json = (const char*)cmd->m_Data;
            dmScript::JsonToLua(L, json, strlen(json)); // throws lua error if it fails
            lua_pushnil(L);
        } else {
            dmLogError("IAP error, purchase response was null");
            lua_pushnil(L);
            IAP_PushError(L, "purchase response was null", REASON_UNSPECIFIED);
        }
    } else if (cmd->m_ResponseCode == BILLING_RESPONSE_RESULT_USER_CANCELED) {
        lua_pushnil(L);
        IAP_PushError(L, "user canceled purchase", REASON_USER_CANCELED);
    } else {
        dmLogError("IAP error %d", cmd->m_ResponseCode);
        lua_pushnil(L);
        IAP_PushError(L, "failed to buy product", REASON_UNSPECIFIED);
    }

    dmScript::PCall(L, 3, 0);

    dmScript::TeardownCallback(cmd->m_Callback);

    assert(top == lua_gettop(L));
}

static dmExtension::Result InitializeIAP(dmExtension::Params* params)
{
    IAP_Queue_Create(&g_IAP.m_CommandQueue);

    g_IAP.m_autoFinishTransactions = dmConfigFile::GetInt(params->m_ConfigFile, "iap.auto_finish_transactions", 1) == 1;

    dmAndroid::ThreadAttacher threadAttacher;
    JNIEnv* env = threadAttacher.GetEnv();

    const char* provider = dmConfigFile::GetString(params->m_ConfigFile, "android.iap_provider", "GooglePlay");
    const char* class_name = "com.defold.iap.IapGooglePlay";

    g_IAP.m_ProviderId = PROVIDER_ID_GOOGLE;
    if (!strcmp(provider, "Amazon")) {
        g_IAP.m_ProviderId = PROVIDER_ID_AMAZON;
        class_name = "com.defold.iap.IapAmazon";
    }
    else if (!strcmp(provider, "Samsung")) {
        g_IAP.m_ProviderId = PROVIDER_ID_SAMSUNG;
        class_name = "com.defold.iap.IapSamsung";
    }
    else if (strcmp(provider, "GooglePlay")) {
        dmLogWarning("Unknown IAP provider name [%s], defaulting to GooglePlay", provider);
    }

    jclass iap_class = dmAndroid::LoadClass(env, class_name);
    jclass iap_jni_class = dmAndroid::LoadClass(env, "com.defold.iap.IapJNI");

    g_IAP.m_List = env->GetMethodID(iap_class, "listItems", "(Ljava/lang/String;Lcom/defold/iap/IListProductsListener;J)V");
    g_IAP.m_Buy = env->GetMethodID(iap_class, "buy", "(Ljava/lang/String;Ljava/lang/String;Lcom/defold/iap/IPurchaseListener;)V");
    g_IAP.m_Restore = env->GetMethodID(iap_class, "restore", "(Lcom/defold/iap/IPurchaseListener;)V");
    g_IAP.m_Stop = env->GetMethodID(iap_class, "stop", "()V");
    g_IAP.m_ProcessPendingConsumables = env->GetMethodID(iap_class, "processPendingConsumables", "(Lcom/defold/iap/IPurchaseListener;)V");
    g_IAP.m_FinishTransaction = env->GetMethodID(iap_class, "finishTransaction", "(Ljava/lang/String;Lcom/defold/iap/IPurchaseListener;)V");
    g_IAP.m_AcknowledgeTransaction = env->GetMethodID(iap_class, "acknowledgeTransaction", "(Ljava/lang/String;Lcom/defold/iap/IPurchaseListener;)V");

    jmethodID jni_constructor = env->GetMethodID(iap_class, "<init>", "(Landroid/app/Activity;Z)V");
    g_IAP.m_IAP = env->NewGlobalRef(env->NewObject(iap_class, jni_constructor, threadAttacher.GetActivity()->clazz, g_IAP.m_autoFinishTransactions));

    jni_constructor = env->GetMethodID(iap_jni_class, "<init>", "()V");
    g_IAP.m_IAPJNI = env->NewGlobalRef(env->NewObject(iap_jni_class, jni_constructor));

    lua_State*L = params->m_L;
    int top = lua_gettop(L);
    luaL_register(L, LIB_NAME, IAP_methods);

    IAP_PushConstants(L);

    lua_pop(L, 1);
    assert(top == lua_gettop(L));

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

    if (cmd->m_Data) {
        free(cmd->m_Data);
    }
}

static dmExtension::Result UpdateIAP(dmExtension::Params* params)
{
    IAP_Queue_Flush(&g_IAP.m_CommandQueue, IAP_OnCommand, 0);
    return dmExtension::RESULT_OK;
}

static dmExtension::Result FinalizeIAP(dmExtension::Params* params)
{
    IAP_Queue_Destroy(&g_IAP.m_CommandQueue);

    if (params->m_L == dmScript::GetCallbackLuaContext(g_IAP.m_Listener)) {
        dmScript::DestroyCallback(g_IAP.m_Listener);
        g_IAP.m_Listener = 0;
    }

    dmAndroid::ThreadAttacher threadAttacher;
    JNIEnv* env = threadAttacher.GetEnv();
    env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_Stop);
    env->DeleteGlobalRef(g_IAP.m_IAP);
    env->DeleteGlobalRef(g_IAP.m_IAPJNI);
    g_IAP.m_IAP = NULL;
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(IAPExt, "IAP", 0, 0, InitializeIAP, UpdateIAP, 0, FinalizeIAP)

#endif //DM_PLATFORM_ANDROID
