#if defined(DM_PLATFORM_ANDROID)

#include <dmsdk/sdk.h>
#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include "iap.h"
#include "iap_private.h"

#define LIB_NAME "iap"

static JNIEnv* Attach()
{
    JNIEnv* env;
    dmGraphics::GetNativeAndroidJavaVM()->AttachCurrentThread(&env, NULL);
    return env;
}

static void Detach()
{
    dmGraphics::GetNativeAndroidJavaVM()->DetachCurrentThread();
}


struct IAP
{
    IAP()
    {
        memset(this, 0, sizeof(*this));
        m_autoFinishTransactions = true;
        m_ProviderId = PROVIDER_ID_GOOGLE;
    }
    int             m_InitCount;
    bool            m_autoFinishTransactions;
    int             m_ProviderId;

    dmScript::LuaCallbackInfo* m_ProductCallback;
    dmScript::LuaCallbackInfo* m_Listener;

    jobject         m_IAP;
    jobject         m_IAPJNI;
    jmethodID       m_List;
    jmethodID       m_Stop;
    jmethodID       m_Buy;
    jmethodID       m_Restore;
    jmethodID       m_ProcessPendingConsumables;
    jmethodID       m_FinishTransaction;

    IAPCommandQueue m_CommandQueue;
};

static IAP g_IAP;

static int IAP_List(lua_State* L)
{
    int top = lua_gettop(L);
    char* buf = IAP_List_CreateBuffer(L);
    if( buf == 0 )
    {
        assert(top == lua_gettop(L));
        return 0;
    }

    if (g_IAP.m_ProductCallback)
        dmScript::DestroyCallback(g_IAP.m_ProductCallback);

    g_IAP.m_ProductCallback = dmScript::CreateCallback(L, 2);

    JNIEnv* env = Attach();
    jstring products = env->NewStringUTF(buf);
    env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_List, products, g_IAP.m_IAPJNI);
    env->DeleteLocalRef(products);
    Detach();

    free(buf);
    assert(top == lua_gettop(L));
    return 0;
}

static int IAP_Buy(lua_State* L)
{
    int top = lua_gettop(L);

    const char* id = luaL_checkstring(L, 1);

    JNIEnv* env = Attach();
    jstring ids = env->NewStringUTF(id);
    env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_Buy, ids, g_IAP.m_IAPJNI);
    env->DeleteLocalRef(ids);
    Detach();

    assert(top == lua_gettop(L));
    return 0;
}

static int IAP_Finish(lua_State* L)
{
    if(g_IAP.m_autoFinishTransactions)
    {
        dmLogWarning("Calling iap.finish when autofinish transactions is enabled. Ignored.");
        return 0;
    }

    int top = lua_gettop(L);

    luaL_checktype(L, 1, LUA_TTABLE);

    lua_getfield(L, -1, "state");
    if (lua_isnumber(L, -1))
    {
        if(lua_tointeger(L, -1) != TRANS_STATE_PURCHASED)
        {
            dmLogError("Invalid transaction state (must be iap.TRANS_STATE_PURCHASED).");
            lua_pop(L, 1);
            assert(top == lua_gettop(L));
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

        JNIEnv* env = Attach();
        jstring receiptUTF = env->NewStringUTF(receipt);
        env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_FinishTransaction, receiptUTF, g_IAP.m_IAPJNI);
        env->DeleteLocalRef(receiptUTF);
        Detach();
    }

    assert(top == lua_gettop(L));
    return 0;
}

static int IAP_Restore(lua_State* L)
{
    // TODO: Missing callback here for completion/error
    // See iap_ios.mm

    int top = lua_gettop(L);
    JNIEnv* env = Attach();
    env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_Restore, g_IAP.m_IAPJNI);
    Detach();

    assert(top == lua_gettop(L));

    lua_pushboolean(L, 1);
    return 1;
}

static int IAP_SetListener(lua_State* L)
{
    IAP* iap = &g_IAP;

    bool had_previous = iap->m_Listener != 0;

    if (iap->m_Listener)
        dmScript::DestroyCallback(iap->m_Listener);

    iap->m_Listener = dmScript::CreateCallback(L, 1);

    // On first set listener, trigger process old ones.
    if (!had_previous) {
        JNIEnv* env = Attach();
        env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_ProcessPendingConsumables, g_IAP.m_IAPJNI);
        Detach();
    }
    return 0;
}

static int IAP_GetProviderId(lua_State* L)
{
    lua_pushinteger(L, g_IAP.m_ProviderId);
    return 1;
}

static const luaL_reg IAP_methods[] =
{
    {"list", IAP_List},
    {"buy", IAP_Buy},
    {"finish", IAP_Finish},
    {"restore", IAP_Restore},
    {"set_listener", IAP_SetListener},
    {"get_provider_id", IAP_GetProviderId},
    {0, 0}
};


#ifdef __cplusplus
extern "C" {
#endif


JNIEXPORT void JNICALL Java_com_defold_iap_IapJNI_onProductsResult__ILjava_lang_String_2(JNIEnv* env, jobject, jint responseCode, jstring productList)
{
    const char* pl = 0;
    if (productList)
    {
        pl = env->GetStringUTFChars(productList, 0);
    }

    IAPCommand cmd;
    cmd.m_Callback = g_IAP.m_ProductCallback;
    cmd.m_Command = IAP_PRODUCT_RESULT;
    cmd.m_ResponseCode = responseCode;
    if (pl)
    {
        cmd.m_Data = strdup(pl);
        env->ReleaseStringUTFChars(productList, pl);
    }
    IAP_Queue_Push(&g_IAP.m_CommandQueue, &cmd);
}

JNIEXPORT void JNICALL Java_com_defold_iap_IapJNI_onPurchaseResult__ILjava_lang_String_2(JNIEnv* env, jobject, jint responseCode, jstring purchaseData)
{
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
        dmJson::Document doc;
        dmJson::Result r = dmJson::Parse((const char*) cmd->m_Data, &doc);
        if (r == dmJson::RESULT_OK && doc.m_NodeCount > 0) {
            char err_str[128];
            if (dmScript::JsonToLua(L, &doc, 0, err_str, sizeof(err_str)) < 0) {
                dmLogError("Failed converting product result JSON to Lua; %s", err_str);
                lua_pushnil(L);
                IAP_PushError(L, "failed to convert JSON to Lua for product response", REASON_UNSPECIFIED);
            } else {
                lua_pushnil(L);
            }
        } else {
            dmLogError("Failed to parse product response (%d)", r);
            lua_pushnil(L);
            IAP_PushError(L, "failed to parse product response", REASON_UNSPECIFIED);
        }
        dmJson::Free(&doc);
    } else {
        dmLogError("IAP error %d", cmd->m_ResponseCode);
        lua_pushnil(L);
        IAP_PushError(L, "failed to fetch product", REASON_UNSPECIFIED);
    }

    dmScript::PCall(L, 3, 0);

    dmScript::TeardownCallback(cmd->m_Callback);
    dmScript::DestroyCallback(cmd->m_Callback);
    assert(g_IAP.m_ProductCallback == cmd->m_Callback);
    g_IAP.m_ProductCallback = 0;

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
            dmJson::Document doc;
            dmJson::Result r = dmJson::Parse((const char*) cmd->m_Data, &doc);
            if (r == dmJson::RESULT_OK && doc.m_NodeCount > 0) {
                char err_str[128];
                if (dmScript::JsonToLua(L, &doc, 0, err_str, sizeof(err_str)) < 0) {
                    dmLogError("Failed converting purchase JSON result to Lua; %s", err_str);
                    lua_pushnil(L);
                    IAP_PushError(L, "failed to convert purchase response JSON to Lua", REASON_UNSPECIFIED);
                } else {
                    lua_pushnil(L);
                }
            } else {
                dmLogError("Failed to parse purchase response (%d)", r);
                lua_pushnil(L);
                IAP_PushError(L, "failed to parse purchase response", REASON_UNSPECIFIED);
            }
            dmJson::Free(&doc);
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
    // TODO: Life-cycle managaemnt is *budget*. No notion of "static initalization"
    // Extend extension functionality with per system initalization?
    if (g_IAP.m_InitCount == 0) {
        IAP_Queue_Create(&g_IAP.m_CommandQueue);

        g_IAP.m_autoFinishTransactions = dmConfigFile::GetInt(params->m_ConfigFile, "iap.auto_finish_transactions", 1) == 1;

        JNIEnv* env = Attach();

        jclass activity_class = env->FindClass("android/app/NativeActivity");
        jmethodID get_class_loader = env->GetMethodID(activity_class,"getClassLoader", "()Ljava/lang/ClassLoader;");
        jobject cls = env->CallObjectMethod(dmGraphics::GetNativeAndroidActivity(), get_class_loader);
        jclass class_loader = env->FindClass("java/lang/ClassLoader");
        jmethodID find_class = env->GetMethodID(class_loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");

        const char* provider = dmConfigFile::GetString(params->m_ConfigFile, "android.iap_provider", "GooglePlay");
        const char* class_name = "com.defold.iap.IapGooglePlay";

        g_IAP.m_ProviderId = PROVIDER_ID_GOOGLE;
        if (!strcmp(provider, "Amazon")) {
            g_IAP.m_ProviderId = PROVIDER_ID_AMAZON;
            class_name = "com.defold.iap.IapAmazon";
        }
        else if (strcmp(provider, "GooglePlay")) {
            dmLogWarning("Unknown IAP provider name [%s], defaulting to GooglePlay", provider);
        }

        jstring str_class_name = env->NewStringUTF(class_name);

        jclass iap_class = (jclass)env->CallObjectMethod(cls, find_class, str_class_name);
        env->DeleteLocalRef(str_class_name);

        str_class_name = env->NewStringUTF("com.defold.iap.IapJNI");
        jclass iap_jni_class = (jclass)env->CallObjectMethod(cls, find_class, str_class_name);
        env->DeleteLocalRef(str_class_name);

        g_IAP.m_List = env->GetMethodID(iap_class, "listItems", "(Ljava/lang/String;Lcom/defold/iap/IListProductsListener;)V");
        g_IAP.m_Buy = env->GetMethodID(iap_class, "buy", "(Ljava/lang/String;Lcom/defold/iap/IPurchaseListener;)V");
        g_IAP.m_Restore = env->GetMethodID(iap_class, "restore", "(Lcom/defold/iap/IPurchaseListener;)V");
        g_IAP.m_Stop = env->GetMethodID(iap_class, "stop", "()V");
        g_IAP.m_ProcessPendingConsumables = env->GetMethodID(iap_class, "processPendingConsumables", "(Lcom/defold/iap/IPurchaseListener;)V");
        g_IAP.m_FinishTransaction = env->GetMethodID(iap_class, "finishTransaction", "(Ljava/lang/String;Lcom/defold/iap/IPurchaseListener;)V");

        jmethodID jni_constructor = env->GetMethodID(iap_class, "<init>", "(Landroid/app/Activity;Z)V");
        g_IAP.m_IAP = env->NewGlobalRef(env->NewObject(iap_class, jni_constructor, dmGraphics::GetNativeAndroidActivity(), g_IAP.m_autoFinishTransactions));

        jni_constructor = env->GetMethodID(iap_jni_class, "<init>", "()V");
        g_IAP.m_IAPJNI = env->NewGlobalRef(env->NewObject(iap_jni_class, jni_constructor));

        Detach();
    }
    g_IAP.m_InitCount++;

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
    --g_IAP.m_InitCount;

    if (params->m_L == dmScript::GetCallbackLuaContext(g_IAP.m_Listener)) {
        dmScript::DestroyCallback(g_IAP.m_Listener);
        g_IAP.m_Listener = 0;
    }

    if (g_IAP.m_InitCount == 0) {
        JNIEnv* env = Attach();
        env->CallVoidMethod(g_IAP.m_IAP, g_IAP.m_Stop);
        env->DeleteGlobalRef(g_IAP.m_IAP);
        env->DeleteGlobalRef(g_IAP.m_IAPJNI);
        Detach();
        g_IAP.m_IAP = NULL;
    }
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(IAPExt, "IAP", 0, 0, InitializeIAP, UpdateIAP, 0, FinalizeIAP)

#endif //DM_PLATFORM_ANDROID

